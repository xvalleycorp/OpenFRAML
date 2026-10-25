//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.benchmark;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.ThreadParams;
import vn.io.dxa.openframl.gate.Gate;
import vn.io.dxa.openframl.gate.GateOutcome;
import vn.io.dxa.openframl.gate.Score;
import vn.io.dxa.openframl.gate.memory.InMemoryVelocityStore;
import vn.io.dxa.openframl.pack.CountryPack;

/**
 * One gate decision in process, with the Vietnam pack and the synthetic bank: watchlist, profiles, velocity,
 * optionally a model, and the decision table. No network. Sample-time mode reports the latency distribution
 * (p50 to p99.99); run with {@code -t} threads for contention.
 *
 * <pre>
 * java -jar openframl-benchmark/target/openframl-benchmark.jar GateBenchmark -t 8
 * </pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
@Fork(value = 1, jvmArgsAppend = {"-XX:+UseZGC", "-XX:+ZGenerational", "-Xms4g", "-Xmx4g"})
public class GateBenchmark {

    private static final Duration BUDGET = Duration.ofMillis(30);
    private static final Score STUB_SCORE = new Score("stub-1", new BigDecimal("0.12"));

    /**
     * {@code none}: rules only. {@code stub}: a model that answers at once, which measures what handing scoring to
     * another thread costs.
     */
    @Param({"none", "stub"})
    public String model;

    @Param({"100000"})
    public int customers;

    private SyntheticBank bank;
    private Gate gate;

    @Setup(Level.Trial)
    public void setUp() {
        bank = new SyntheticBank(customers, 42);
        Gate.Builder builder = Gate.builder(CountryPack.forCountry("VN")).profiles(bank.profiles())
                .velocity(new InMemoryVelocityStore()).watchlist(bank.watchlist());
        if (model.equals("stub")) {
            builder.scoreProvider((event, profiles, velocity) -> STUB_SCORE);
        }
        gate = builder.build();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        gate.close();
    }

    /** Each benchmark thread pays for its own slice of customers, as a node does for the accounts routed to it. */
    @State(Scope.Thread)
    public static class Payments {

        SyntheticBank.Feed feed;

        @Setup(Level.Trial)
        public void setUp(GateBenchmark benchmark, ThreadParams thread, BenchmarkParams params) {
            feed = benchmark.bank.feed(thread.getThreadIndex(), params.getThreads(), 7);
        }
    }

    @Benchmark
    public GateOutcome decide(Payments payments) {
        return gate.decide(payments.feed.next(), BUDGET);
    }
}
