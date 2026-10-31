//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.stream;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import vn.io.dxa.openframl.cases.CaseService;
import vn.io.dxa.openframl.cases.CaseStore;
import vn.io.dxa.openframl.cases.jdbc.DriverDataSource;
import vn.io.dxa.openframl.cases.jdbc.JdbcCaseStore;
import vn.io.dxa.openframl.cases.memory.InMemoryCaseStore;
import vn.io.dxa.openframl.model.TransactionEvent;
import vn.io.dxa.openframl.monitor.ActivityStore;
import vn.io.dxa.openframl.monitor.MonitorPolicy;
import vn.io.dxa.openframl.monitor.TransactionMonitor;
import vn.io.dxa.openframl.monitor.memory.InMemoryActivityStore;
import vn.io.dxa.openframl.pack.CountryPack;
import vn.io.dxa.openframl.pack.ListSource;
import vn.io.dxa.openframl.screen.NameRules;
import vn.io.dxa.openframl.screen.PaymentScreening;
import vn.io.dxa.openframl.screen.ScreeningDirectory;
import vn.io.dxa.openframl.valkey.Valkey;
import vn.io.dxa.openframl.valkey.ValkeyActivityStore;

/**
 * The Stream Service: reads the bank's transaction topic and runs payment screening and monitoring on each
 * transaction, raising their alerts in Case Management. Configured from the environment; see
 * {@code docs/stream.md}.
 */
public final class StreamService {

    private static final System.Logger LOG = System.getLogger(StreamService.class.getName());

    private StreamService() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        Map<String, String> env = System.getenv();
        CountryPack pack = CountryPack.forCountry(env.getOrDefault("OPENFRAML_COUNTRY", "VN"));
        CaseService cases = new CaseService(caseStore(env), Clock.systemUTC());

        List<Consumer<TransactionEvent>> handlers = new ArrayList<>();
        ScreeningDirectory lists = null;
        String listDir = env.get("OPENFRAML_STREAM_SCREENING_DIR");
        if (listDir != null) {
            lists = ScreeningDirectory.start(Path.of(listDir), NameRules.of(pack), Duration.ofSeconds(5));
            for (ListSource source : lists.missing(pack.screeningLists())) {
                LOG.log(System.Logger.Level.WARNING, "screening list {0} ({1}, {2}) expected by country pack {3} is "
                        + "not in {4}", source.id(), source.name(), source.publisher(), pack.countryCode(), listDir);
            }
            handlers.add(new PaymentScreening(lists::current,
                    Double.parseDouble(env.getOrDefault("OPENFRAML_STREAM_SCREENING_THRESHOLD", "0.85")),
                    cases::raise, 100_000)::screen);
        } else {
            LOG.log(System.Logger.Level.WARNING, "OPENFRAML_STREAM_SCREENING_DIR not set: payments are not "
                    + "screened");
        }
        if (Boolean.parseBoolean(env.getOrDefault("OPENFRAML_STREAM_MONITOR", "true"))) {
            handlers.add(new TransactionMonitor(pack, MonitorPolicy.of(pack), activityStore(env),
                    cases::raise, Duration.ofDays(Long.parseLong(
                            env.getOrDefault("OPENFRAML_STREAM_SUPPRESSION_DAYS", "7"))))::process);
        }

        String topic = env.getOrDefault("OPENFRAML_STREAM_TOPIC", "openframl.transactions");
        TransactionStream stream = new TransactionStream(new KafkaConsumer<>(consumerConfig(env,
                env.getOrDefault("OPENFRAML_STREAM_GROUP", "openframl-stream"))), topic, handlers,
                Duration.ofSeconds(5));
        Thread runner = Thread.ofPlatform().name("openframl-stream").start(stream);
        ScreeningDirectory screening = lists;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stream.stop();
            try {
                runner.join(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (screening != null) {
                screening.close();
            }
        }));
        LOG.log(System.Logger.Level.INFO, "stream service reading {0} with {1} handler(s), country pack {2} {3}",
                topic, handlers.size(), pack.countryCode(), pack.version());
        runner.join();
    }

    static ActivityStore activityStore(Map<String, String> env) {
        String url = env.get("OPENFRAML_STREAM_VALKEY_URL");
        if (url == null) {
            LOG.log(System.Logger.Level.WARNING, "OPENFRAML_STREAM_VALKEY_URL not set: account activity is kept in "
                    + "this process; run one Stream Service per consumer group");
            return new InMemoryActivityStore();
        }
        return new ValkeyActivityStore(Valkey.connect(url, Duration.ofMillis(Long.parseLong(
                env.getOrDefault("OPENFRAML_STREAM_VALKEY_TIMEOUT_MS", "500")))));
    }

    static CaseStore caseStore(Map<String, String> env) {
        String url = env.get("OPENFRAML_CASE_JDBC_URL");
        if (url == null) {
            LOG.log(System.Logger.Level.WARNING, "OPENFRAML_CASE_JDBC_URL not set: alerts are kept in memory, for "
                    + "trials only");
            return new InMemoryCaseStore();
        }
        return new JdbcCaseStore(new DriverDataSource(url));
    }

    /**
     * @param group the consumer group, or {@code null} for a consumer that is assigned partitions
     */
    static Map<String, Object> consumerConfig(Map<String, String> env, String group) {
        String bootstrap = env.get("OPENFRAML_STREAM_KAFKA_BOOTSTRAP");
        if (bootstrap == null) {
            throw new IllegalStateException("OPENFRAML_STREAM_KAFKA_BOOTSTRAP is required");
        }
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, "openframl-stream");
        if (group != null) {
            config.put(ConsumerConfig.GROUP_ID_CONFIG, group);
            config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        }
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return config;
    }
}
