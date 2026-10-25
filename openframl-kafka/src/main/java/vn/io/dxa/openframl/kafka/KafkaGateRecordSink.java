//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.kafka;

import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import vn.io.dxa.openframl.gate.GateRecord;
import vn.io.dxa.openframl.gate.GateRecordJson;
import vn.io.dxa.openframl.gate.GateRecordSink;

/**
 * Publishes every gate decision record to a Kafka topic, keyed by event id, as one line of
 * {@linkplain GateRecordJson JSON}.
 *
 * <p>{@link #accept} only puts the record on a bounded in-memory queue and returns; one sender thread serialises and
 * sends. A Kafka producer can block the caller (waiting for metadata, or for buffer space when the brokers are slow)
 * and the decision path must never wait for it. When the queue is full the record is dropped and counted in
 * {@link #dropped()}: the decision itself is never held back to save its record. Size the queue for the longest
 * broker outage the bank accepts losing records for, and alert on {@link #dropped()} and {@link #failed()}.
 */
public final class KafkaGateRecordSink implements GateRecordSink, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(KafkaGateRecordSink.class.getName());

    /** Default queue size: about 10 seconds at 10,000 TPS. */
    public static final int DEFAULT_CAPACITY = 100_000;

    private final Producer<String, String> producer;
    private final String topic;
    private final BlockingQueue<GateRecord> queue;
    private final Thread sender;
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile boolean closing;

    /**
     * Uses {@code producer}, which this sink closes. Most callers want {@link #connect}.
     */
    public KafkaGateRecordSink(Producer<String, String> producer, String topic, int capacity) {
        this.producer = Objects.requireNonNull(producer, "producer");
        this.topic = Objects.requireNonNull(topic, "topic");
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.sender = Thread.ofPlatform().name("openframl-kafka-records").daemon().start(this::run);
    }

    /**
     * A sink with its own producer, configured for an audit trail: every in-sync replica acknowledges, retries do
     * not duplicate, and batches are compressed with lz4.
     *
     * @param bootstrapServers the brokers, e.g. {@code kafka-1:9092,kafka-2:9092}
     * @param overrides        any other producer settings (security, client id...), applied last
     */
    public static KafkaGateRecordSink connect(String bootstrapServers, String topic, Map<String, Object> overrides) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "openframl-gate");
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        config.putAll(overrides);
        return new KafkaGateRecordSink(
                new KafkaProducer<>(config, new StringSerializer(), new StringSerializer()), topic, DEFAULT_CAPACITY);
    }

    @Override
    public void accept(GateRecord record) {
        if (closing || !queue.offer(record)) {
            long count = dropped.incrementAndGet();
            if (count == 1 || count % 10_000 == 0) {
                LOG.log(ERROR, "decision records dropped: {0} so far (queue full or sink closed)", count);
            }
        }
    }

    private void run() {
        while (!closing || !queue.isEmpty()) {
            GateRecord record;
            try {
                record = queue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (record != null) {
                send(record);
            }
        }
    }

    private void send(GateRecord record) {
        try {
            producer.send(new ProducerRecord<>(topic, record.eventId(), GateRecordJson.write(record)),
                    (metadata, error) -> {
                        if (error == null) {
                            sent.incrementAndGet();
                        } else {
                            failed(record, error);
                        }
                    });
        } catch (RuntimeException e) {
            failed(record, e);
        }
    }

    private void failed(GateRecord record, Exception error) {
        failed.incrementAndGet();
        LOG.log(WARNING, "decision record for event {0} not published: {1}", record.eventId(),
                error.getClass().getName());
    }

    /** Records the brokers acknowledged. */
    public long sent() {
        return sent.get();
    }

    /** Records lost because the queue was full or the sink was closed. */
    public long dropped() {
        return dropped.get();
    }

    /** Records the producer gave up on. */
    public long failed() {
        return failed.get();
    }

    /** Records waiting in the queue. */
    public int pending() {
        return queue.size();
    }

    /** Stops accepting records, sends the queued ones, and closes the producer; waits up to 10 seconds. */
    @Override
    public void close() {
        close(Duration.ofSeconds(10));
    }

    public void close(Duration timeout) {
        closing = true;
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            sender.join(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (sender.isAlive()) {
            sender.interrupt();
            LOG.log(ERROR, "decision records not sent before close: {0}", queue.size());
        }
        producer.close(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
    }
}
