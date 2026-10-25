//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.benchmark;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import vn.io.dxa.openframl.gate.Gate;
import vn.io.dxa.openframl.gate.GateRecord;
import vn.io.dxa.openframl.gate.memory.InMemoryVelocityStore;
import vn.io.dxa.openframl.gate.server.GateGrpcService;
import vn.io.dxa.openframl.gate.server.GateServer;
import vn.io.dxa.openframl.gate.v1.DecideResponse;
import vn.io.dxa.openframl.gate.v1.Decision;
import vn.io.dxa.openframl.gate.v1.Fallback;
import vn.io.dxa.openframl.gate.v1.GateServiceGrpc;
import vn.io.dxa.openframl.pack.CountryPack;

/**
 * Open-loop load test of the gate over gRPC: payments are sent on a fixed schedule whether or not earlier ones have
 * been answered, and each latency is measured from the moment the payment was due to be sent. A slow server
 * therefore shows up in the percentiles instead of slowing the sender down (no coordinated omission).
 *
 * <pre>
 * java -XX:+UseZGC -XX:+ZGenerational -cp openframl-benchmark/target/openframl-benchmark.jar vn.io.dxa.openframl.benchmark.LoadTest \
 *     --rate=20000 --seconds=60 --warmup=10
 * </pre>
 *
 * <p>Options: {@code --rate} payments per second (20000), {@code --seconds} measured (60), {@code --warmup} seconds
 * not measured (10), {@code --deadline-ms} per call (30), {@code --customers} in the synthetic bank (100000), and
 * {@code --target=host:port} to load a running server instead of an embedded one. An embedded server runs in the
 * same JVM as the sender and also reports the gate's own time per step. The sender keeps one core busy. A remote server started with
 * {@code GateServer} has empty stores until the Valkey stores are in, so it decides without profiles.
 */
public final class LoadTest {

    private static final double[] PERCENTS = {50, 90, 99, 99.9};

    private final Options options;
    private final ManagedChannel channel;
    private final GateServiceGrpc.GateServiceStub stub;
    private final SyntheticBank.Feed feed;
    private final Map<String, Samples> steps = new ConcurrentHashMap<>();
    private final AtomicLong inFlight = new AtomicLong();
    private volatile boolean measuring;
    private Samples latencies;
    private final Map<Decision, LongAdder> decisions = new EnumMap<>(Decision.class);
    private final Map<Fallback, LongAdder> fallbacks = new EnumMap<>(Fallback.class);
    private final Map<Status.Code, LongAdder> errors = new ConcurrentHashMap<>();
    private final LongAdder answered = new LongAdder();
    private long behindSchedule;

    private LoadTest(Options options, String target, SyntheticBank bank) {
        this.options = options;
        this.channel = NettyChannelBuilder.forTarget(target).usePlaintext().directExecutor().build();
        this.stub = GateServiceGrpc.newStub(channel);
        this.feed = bank.feed(0, 1, 7);
        for (Decision decision : Decision.values()) {
            decisions.put(decision, new LongAdder());
        }
        for (Fallback fallback : Fallback.values()) {
            fallbacks.put(fallback, new LongAdder());
        }
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        SyntheticBank bank = new SyntheticBank(options.customers(), 42);
        if (options.target() != null) {
            new LoadTest(options, options.target(), bank).run("remote server " + options.target());
            return;
        }
        LoadTest[] test = new LoadTest[1];
        try (Gate gate = Gate.builder(CountryPack.forCountry("VN")).profiles(bank.profiles())
                .velocity(new InMemoryVelocityStore()).watchlist(bank.watchlist())
                .recordSink(record -> test[0].record(record)).build()) {
            GateServer server = GateServer.start(0,
                    new GateGrpcService(gate, Duration.ofMillis(options.deadlineMs()), Duration.ofMillis(1)));
            try {
                test[0] = new LoadTest(options, "localhost:" + server.port(), bank);
                test[0].run("embedded server");
            } finally {
                server.close();
            }
        }
    }

    private void record(GateRecord record) {
        if (!measuring) {
            return;
        }
        record.stepNanos().forEach((step, nanos) -> samples(step).add(nanos));
        samples("total").add(record.totalNanos());
    }

    private Samples samples(String step) {
        return steps.computeIfAbsent(step, name -> new Samples(options.rate() * options.seconds()));
    }

    private void run(String server) throws InterruptedException {
        System.out.printf(Locale.ROOT, "OpenFRAML gate load test: %,d payments/s, %d s warm-up then %d s measured, "
                        + "deadline %d ms, %,d customers, %s%n", options.rate(), options.warmup(), options.seconds(),
                options.deadlineMs(), options.customers(), server);
        latencies = new Samples(options.rate() * options.seconds());
        send(options.warmup(), false);
        steps.clear();
        long sent = send(options.seconds(), true);
        report(sent);
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    /** Sends {@code rate} payments a second for {@code seconds}, then waits for the answers. */
    private long send(int seconds, boolean measured) throws InterruptedException {
        long total = (long) options.rate() * seconds;
        double interval = 1e9 / options.rate();
        long start = System.nanoTime();
        measuring = measured;
        for (long i = 0; i < total; i++) {
            long due = start + (long) (i * interval);
            long wait = due - System.nanoTime();
            while (wait > 0) {
                // Sleeping can overshoot by a millisecond or more, so the sender spins for the last 2 ms
                if (wait > 2_000_000) {
                    LockSupport.parkNanos(wait - 1_000_000);
                } else {
                    Thread.onSpinWait();
                }
                wait = due - System.nanoTime();
            }
            if (measured && wait < -1_000_000) {
                behindSchedule++;
            }
            inFlight.incrementAndGet();
            stub.withDeadlineAfter(options.deadlineMs(), TimeUnit.MILLISECONDS)
                    .decide(Requests.of(feed.next()), new Answer(due, measured));
        }
        long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (inFlight.get() > 0 && System.nanoTime() < waitUntil) {
            Thread.sleep(10);
        }
        measuring = false;
        return total;
    }

    private final class Answer implements StreamObserver<DecideResponse> {

        private final long due;
        private final boolean measured;

        Answer(long due, boolean measured) {
            this.due = due;
            this.measured = measured;
        }

        @Override
        public void onNext(DecideResponse response) {
            if (measured) {
                latencies.add(System.nanoTime() - due);
                answered.increment();
                decisions.get(response.getDecision()).increment();
                fallbacks.get(response.getFallback()).increment();
            }
        }

        @Override
        public void onError(Throwable error) {
            if (measured) {
                errors.computeIfAbsent(Status.fromThrowable(error).getCode(), code -> new LongAdder()).increment();
            }
            inFlight.decrementAndGet();
        }

        @Override
        public void onCompleted() {
            inFlight.decrementAndGet();
        }
    }

    private void report(long sent) {
        long ok = answered.sum();
        System.out.printf(Locale.ROOT, "%nSent %,d, answered %,d, errors %s%n", sent, ok, errors.isEmpty() ? "none"
                : errors.entrySet().stream().map(e -> e.getKey() + " " + e.getValue().sum()).toList());
        System.out.printf("%-22s %9s %9s %9s %9s %9s%n", "Latency (ms)", "p50", "p90", "p99", "p99.9", "max");
        line("client, end to end", latencies);
        Map<String, Samples> ordered = new LinkedHashMap<>();
        for (String step : new String[] {"total", "watchlist", "profiles", "velocity", "model", "rules", "fallback"}) {
            if (steps.containsKey(step)) {
                ordered.put(step, steps.get(step));
            }
        }
        ordered.forEach((step, samples) -> line("gate " + step, samples));
        System.out.printf(Locale.ROOT, "%nDecisions: %s%n", shares(decisions, ok));
        System.out.printf(Locale.ROOT, "Fallbacks: %s%n", shares(fallbacks, ok));
        if (behindSchedule > 0) {
            System.out.printf(Locale.ROOT, "The sender fell more than 1 ms behind schedule %,d times: the machine is "
                    + "saturated and the latencies above include that.%n", behindSchedule);
        }
        long p99 = latencies.percentiles(99)[0];
        System.out.printf(Locale.ROOT, "%nTarget p99 under 30 ms: %s in this run (p99 %.2f ms)%n",
                p99 < TimeUnit.MILLISECONDS.toNanos(30) && errors.isEmpty() ? "met" : "NOT met", p99 / 1e6);
    }

    private static void line(String name, Samples samples) {
        long[] values = samples.percentiles(PERCENTS);
        System.out.printf(Locale.ROOT, "%-22s %9.3f %9.3f %9.3f %9.3f %9.3f%n", name, values[0] / 1e6,
                values[1] / 1e6, values[2] / 1e6, values[3] / 1e6, values[4] / 1e6);
    }

    private static <E extends Enum<E>> String shares(Map<E, LongAdder> counts, long total) {
        StringBuilder text = new StringBuilder();
        counts.forEach((key, count) -> {
            if (count.sum() > 0) {
                String name = key.name().replaceFirst("^[A-Z]+_", "");
                text.append(text.isEmpty() ? "" : ", ").append(name.equals("UNSPECIFIED") ? "none" : name)
                        .append(String.format(Locale.ROOT, " %.2f%%", 100.0 * count.sum() / Math.max(1, total)));
            }
        });
        return text.isEmpty() ? "none" : text.toString();
    }

    record Options(int rate, int seconds, int warmup, int deadlineMs, int customers, String target) {

        static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>(Map.of("rate", "20000", "seconds", "60", "warmup", "10",
                    "deadline-ms", "30", "customers", "100000"));
            for (String arg : args) {
                String[] pair = arg.replaceFirst("^--", "").split("=", 2);
                if (pair.length != 2 || !(values.containsKey(pair[0]) || pair[0].equals("target"))) {
                    throw new IllegalArgumentException("unknown option " + arg + "; options: --rate, --seconds, "
                            + "--warmup, --deadline-ms, --customers, --target");
                }
                values.put(pair[0], pair[1]);
            }
            return new Options(Integer.parseInt(values.get("rate")), Integer.parseInt(values.get("seconds")),
                    Integer.parseInt(values.get("warmup")), Integer.parseInt(values.get("deadline-ms")),
                    Integer.parseInt(values.get("customers")), values.get("target"));
        }
    }
}
