//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate.server;

import ai.onnxruntime.OrtException;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import vn.io.dxa.openframl.gate.Gate;
import vn.io.dxa.openframl.gate.GatePolicy;
import vn.io.dxa.openframl.gate.GateRecordSink;
import vn.io.dxa.openframl.gate.ProfileStore;
import vn.io.dxa.openframl.gate.VelocityStore;
import vn.io.dxa.openframl.gate.memory.InMemoryProfileStore;
import vn.io.dxa.openframl.gate.memory.InMemoryVelocityStore;
import vn.io.dxa.openframl.gate.memory.InMemoryWatchlist;
import vn.io.dxa.openframl.kafka.KafkaGateRecordSink;
import vn.io.dxa.openframl.onnx.OnnxScoreProvider;
import vn.io.dxa.openframl.pack.CountryPack;
import vn.io.dxa.openframl.valkey.Valkey;
import vn.io.dxa.openframl.valkey.ValkeyLayout;
import vn.io.dxa.openframl.valkey.ValkeyProfileStore;
import vn.io.dxa.openframl.valkey.ValkeyVelocityStore;

/**
 * Runs the gate as a gRPC server.
 *
 * <p>Configured from the environment:
 * <ul>
 *   <li>{@code OPENFRAML_COUNTRY}: the country pack, default {@code VN};</li>
 *   <li>{@code OPENFRAML_GATE_RULES_DIR}: a directory of the bank's own {@code *.stone.json} tables; without it, the
 *       country pack's sample tables;</li>
 *   <li>{@code OPENFRAML_GATE_PORT}: the port, default {@code 9090};</li>
 *   <li>{@code OPENFRAML_GATE_DEFAULT_BUDGET_MS}: the budget of a call without a deadline, default 30;</li>
 *   <li>{@code OPENFRAML_GATE_REPLY_MARGIN_MS}: kept back from the caller's deadline for the reply, default 1;</li>
 *   <li>{@code OPENFRAML_GATE_KAFKA_BOOTSTRAP}: Kafka brokers to publish decision records to; without it, records
 *       are not kept;</li>
 *   <li>{@code OPENFRAML_GATE_RECORDS_TOPIC}: the topic for decision records, default
 *       {@code openframl.gate.records};</li>
 *   <li>{@code OPENFRAML_GATE_VALKEY_URL}: Valkey for profiles and velocity, e.g. {@code redis://valkey:6379};
 *       without it, empty in-memory stores, for trials and benchmarks only;</li>
 *   <li>{@code OPENFRAML_GATE_VALKEY_TIMEOUT_MS}: how long a Valkey command may take, default 5;</li>
 *   <li>{@code OPENFRAML_GATE_PROFILE_CACHE_SECONDS}: how long the gate caches a profile, default 60;</li>
 *   <li>{@code OPENFRAML_GATE_MODEL}: an ONNX fraud model; without it, decisions are made by the rules alone;</li>
 *   <li>{@code OPENFRAML_GATE_MODEL_BUDGET_MS}: the longest the gate waits for a score, default 5.</li>
 * </ul>
 *
 * <p>The watchlist starts empty until list screening loads it. Embedders build their own {@link Gate} and pass it
 * to {@link #start}.
 */
public final class GateServer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(GateServer.class.getName());

    private final Server server;

    private GateServer(Server server) {
        this.server = server;
    }

    /** Starts serving {@code service} on {@code port}; port 0 picks a free one. */
    public static GateServer start(int port, GateGrpcService service) throws IOException {
        Objects.requireNonNull(service, "service");
        Server server = NettyServerBuilder.forPort(port).addService(service).build().start();
        return new GateServer(server);
    }

    public int port() {
        return server.getPort();
    }

    /** Stops accepting calls and waits up to 10 seconds for the calls in flight. */
    @Override
    public void close() throws InterruptedException {
        server.shutdown();
        if (!server.awaitTermination(10, TimeUnit.SECONDS)) {
            server.shutdownNow();
        }
    }

    public void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }

    public static void main(String[] args) throws IOException, InterruptedException, OrtException {
        Map<String, String> env = System.getenv();
        CountryPack pack = CountryPack.forCountry(env.getOrDefault("OPENFRAML_COUNTRY", "VN"));
        String rulesDir = env.get("OPENFRAML_GATE_RULES_DIR");
        GatePolicy policy = rulesDir == null ? GatePolicy.of(pack) : GatePolicy.fromDirectory(pack, Path.of(rulesDir));
        String kafka = env.get("OPENFRAML_GATE_KAFKA_BOOTSTRAP");
        KafkaGateRecordSink records = kafka == null ? null : KafkaGateRecordSink.connect(kafka,
                env.getOrDefault("OPENFRAML_GATE_RECORDS_TOPIC", "openframl.gate.records"), Map.of());
        if (records == null) {
            LOG.log(System.Logger.Level.WARNING, "OPENFRAML_GATE_KAFKA_BOOTSTRAP not set: decision records are not "
                    + "kept");
        }
        String valkeyUrl = env.get("OPENFRAML_GATE_VALKEY_URL");
        Valkey valkey = valkeyUrl == null ? null : Valkey.connect(valkeyUrl,
                Duration.ofMillis(Long.parseLong(env.getOrDefault("OPENFRAML_GATE_VALKEY_TIMEOUT_MS", "5"))));
        ProfileStore profiles;
        VelocityStore velocity;
        if (valkey == null) {
            LOG.log(System.Logger.Level.WARNING, "OPENFRAML_GATE_VALKEY_URL not set: empty in-memory stores, for "
                    + "trials and benchmarks only");
            profiles = new InMemoryProfileStore();
            velocity = new InMemoryVelocityStore();
        } else {
            profiles = new ValkeyProfileStore(valkey.connection(), ValkeyLayout.DEFAULT,
                    Duration.ofSeconds(Long.parseLong(env.getOrDefault("OPENFRAML_GATE_PROFILE_CACHE_SECONDS", "60"))),
                    1_000_000);
            velocity = new ValkeyVelocityStore(valkey);
        }
        String modelPath = env.get("OPENFRAML_GATE_MODEL");
        OnnxScoreProvider model = modelPath == null ? null
                : OnnxScoreProvider.load(Path.of(modelPath), pack.businessZone());
        Gate.Builder builder = Gate.builder(pack).policy(policy).profiles(profiles).velocity(velocity)
                .watchlist(new InMemoryWatchlist())
                .recordSink(records == null ? GateRecordSink.NONE : records)
                .modelBudget(Duration.ofMillis(Long.parseLong(env.getOrDefault("OPENFRAML_GATE_MODEL_BUDGET_MS", "5"))));
        if (model != null) {
            LOG.log(System.Logger.Level.INFO, "model {0} scores payments from features {1}", model.modelVersion(),
                    model.featureNames());
            builder.scoreProvider(model);
        }
        Gate gate = builder.build();
        GateGrpcService service = new GateGrpcService(gate,
                Duration.ofMillis(Long.parseLong(env.getOrDefault("OPENFRAML_GATE_DEFAULT_BUDGET_MS", "30"))),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("OPENFRAML_GATE_REPLY_MARGIN_MS", "1"))));
        GateServer server = start(Integer.parseInt(env.getOrDefault("OPENFRAML_GATE_PORT", "9090")), service);
        LOG.log(System.Logger.Level.INFO, "gate for {0} (pack {1}, policy {2}) listening on port {3}",
                pack.countryCode(), pack.version(), policy.id(), server.port());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                gate.close();
                if (records != null) {
                    records.close();
                }
                if (valkey != null) {
                    valkey.close();
                }
                if (model != null) {
                    try {
                        model.close();
                    } catch (OrtException e) {
                        LOG.log(System.Logger.Level.WARNING, "model did not close: {0}", e.getMessage());
                    }
                }
            }
        }));
        server.awaitTermination();
    }
}
