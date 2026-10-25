//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import vn.io.dxa.openframl.gate.GateOutcome;
import vn.io.dxa.openframl.gate.GateRecord;
import vn.io.dxa.openframl.model.Decision;

/** Runs against a real broker, e.g. {@code OPENFRAML_TEST_KAFKA=localhost:9092}; CI starts one. */
@EnabledIfEnvironmentVariable(named = "OPENFRAML_TEST_KAFKA", matches = ".+")
class KafkaBrokerTest {

    @Test
    void theBrokerReceivesEveryRecord() {
        String bootstrap = System.getenv("OPENFRAML_TEST_KAFKA");
        String topic = "openframl-test-" + UUID.randomUUID();
        try (KafkaGateRecordSink sink = KafkaGateRecordSink.connect(bootstrap, topic, Map.of())) {
            for (int i = 0; i < 100; i++) {
                sink.accept(new GateRecord("evt-" + i, Instant.parse("2026-10-25T03:00:00Z"),
                        new GateOutcome(Decision.ALLOW, List.of(), "gate_decision@1@sha256:ab", Optional.empty(),
                                false),
                        Optional.empty(), Optional.empty(), Optional.empty(), Map.of("rules", 1L), List.of()));
            }
            sink.close(Duration.ofSeconds(30));
            assertEquals(100, sink.sent());
            assertEquals(0, sink.dropped() + sink.failed());
        }

        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, topic);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            Map<String, String> received = new HashMap<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (received.size() < 100 && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    received.put(record.key(), record.value());
                }
            }
            assertEquals(100, received.size());
            assertTrue(received.get("evt-42").contains("\"eventId\":\"evt-42\""), received.get("evt-42"));
        }
    }
}
