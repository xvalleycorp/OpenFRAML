//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import vn.io.dxa.openframl.gate.GateOutcome;
import vn.io.dxa.openframl.gate.GateRecord;
import vn.io.dxa.openframl.model.Decision;

class KafkaGateRecordSinkTest {

    private static GateRecord record(String eventId) {
        return new GateRecord(eventId, Instant.parse("2026-10-25T03:00:00Z"),
                new GateOutcome(Decision.ALLOW, List.of(), "gate_decision@1@sha256:ab", Optional.empty(), false),
                Optional.empty(), Optional.empty(), Optional.empty(), Map.of("rules", 10_000L), List.of());
    }

    private static MockProducer<String, String> producer(boolean autoComplete) {
        return new MockProducer<>(autoComplete, null, new StringSerializer(), new StringSerializer());
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(5);
        }
    }

    @Test
    void publishesTheRecordAsJsonKeyedByEventId() throws Exception {
        MockProducer<String, String> producer = producer(true);
        try (KafkaGateRecordSink sink = new KafkaGateRecordSink(producer, "openframl.gate.records", 10)) {
            sink.accept(record("evt-1"));
            await(() -> sink.sent() == 1);

            ProducerRecord<String, String> sent = producer.history().getFirst();
            assertEquals("openframl.gate.records", sent.topic());
            assertEquals("evt-1", sent.key());
            assertTrue(sent.value().startsWith("{\"schema\":\"openframl.gate.record/1\",\"eventId\":\"evt-1\""),
                    sent.value());
        }
    }

    @Test
    void aRecordTheBrokersRejectIsCounted() throws Exception {
        MockProducer<String, String> producer = producer(false);
        try (KafkaGateRecordSink sink = new KafkaGateRecordSink(producer, "records", 10)) {
            sink.accept(record("evt-1"));
            await(() -> producer.history().size() == 1);
            producer.errorNext(new IllegalStateException("broker down"));

            assertEquals(1, sink.failed());
            assertEquals(0, sink.sent());
        }
    }

    @Test
    void aFullQueueDropsTheRecordInsteadOfWaiting() throws Exception {
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MockProducer<String, String> stuck = new MockProducer<>(true, null, new StringSerializer(),
                new StringSerializer()) {
            @Override
            public synchronized Future<RecordMetadata> send(ProducerRecord<String, String> record,
                    Callback callback) {
                sending.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.send(record, callback);
            }
        };
        try (KafkaGateRecordSink sink = new KafkaGateRecordSink(stuck, "records", 1)) {
            sink.accept(record("evt-1"));
            assertTrue(sending.await(5, TimeUnit.SECONDS));
            sink.accept(record("evt-2"));

            long start = System.nanoTime();
            sink.accept(record("evt-3"));
            assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(50));
            assertEquals(1, sink.dropped());
            assertEquals(1, sink.pending());
            release.countDown();
            await(() -> sink.sent() == 2);
        }
    }

    @Test
    void closeSendsWhatIsQueuedThenClosesTheProducer() {
        MockProducer<String, String> producer = producer(true);
        KafkaGateRecordSink sink = new KafkaGateRecordSink(producer, "records", 100);
        for (int i = 0; i < 50; i++) {
            sink.accept(record("evt-" + i));
        }
        sink.close(Duration.ofSeconds(5));

        assertEquals(50, producer.history().size());
        assertTrue(producer.closed());
        sink.accept(record("evt-late"));
        assertEquals(1, sink.dropped());
    }
}
