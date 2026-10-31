//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.stream;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import vn.io.dxa.openframl.model.TransactionEvent;

/**
 * Reads the bank's transactions from a Kafka topic and hands each one to the handlers, in order, e.g. payment
 * screening then monitoring.
 *
 * <ul>
 *   <li>At least once: a record's offset is committed only after every handler has taken it. If a handler throws,
 *       nothing after the record is taken, the partitions go back to the first record not taken, and the stream tries
 *       again after a pause. Handlers must therefore be idempotent by event id, as OpenFRAML's are.</li>
 *   <li>A record that is not an event (see {@link TransactionEventJson}) is logged by partition and offset, with the
 *       field that is wrong but never its content, counted, and skipped.</li>
 *   <li>Order: Kafka keeps the order of a partition. The payment hub must key records by the originating account
 *       ({@code AccountRef.key()}), so that monitoring sees each account's transactions in order.</li>
 * </ul>
 *
 * <p>{@link #run} polls until {@link #stop} is called from another thread; the consumer is not thread-safe and is
 * used by the running thread only.
 */
public final class TransactionStream implements Runnable {

    private static final System.Logger LOG = System.getLogger(TransactionStream.class.getName());

    private final org.apache.kafka.clients.consumer.Consumer<String, String> consumer;
    private final String topic;
    private final List<Consumer<TransactionEvent>> handlers;
    private final Duration retryPause;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong taken = new AtomicLong();
    private final AtomicLong invalid = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();

    /**
     * @param consumer   a consumer of string keys and values with auto commit off; the stream subscribes it
     * @param handlers   called for each event, in this order
     * @param retryPause how long to wait before taking a record again after a handler failed
     */
    public TransactionStream(org.apache.kafka.clients.consumer.Consumer<String, String> consumer, String topic,
            List<Consumer<TransactionEvent>> handlers, Duration retryPause) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.topic = Objects.requireNonNull(topic, "topic");
        this.handlers = List.copyOf(handlers);
        this.retryPause = Objects.requireNonNull(retryPause, "retryPause");
    }

    @Override
    public void run() {
        consumer.subscribe(List.of(topic));
        try {
            while (running.get()) {
                pollOnce(Duration.ofMillis(500));
            }
        } catch (WakeupException e) {
            if (running.get()) {
                throw e;
            }
        } finally {
            consumer.close();
        }
    }

    /** One poll: takes the records it returns, and commits what was taken. */
    void pollOnce(Duration timeout) {
        ConsumerRecords<String, String> records = consumer.poll(timeout);
        Map<TopicPartition, OffsetAndMetadata> done = new HashMap<>();
        for (ConsumerRecord<String, String> record : records) {
            TopicPartition partition = new TopicPartition(record.topic(), record.partition());
            if (!take(record)) {
                rewind(records, done);
                commit(done);
                retries.incrementAndGet();
                pause();
                return;
            }
            done.put(partition, new OffsetAndMetadata(record.offset() + 1));
        }
        commit(done);
    }

    /** Whether the record was taken (or skipped as invalid); false if a handler failed. */
    private boolean take(ConsumerRecord<String, String> record) {
        TransactionEvent event;
        try {
            event = TransactionEventJson.read(record.value() == null ? "" : record.value());
        } catch (IllegalArgumentException e) {
            invalid.incrementAndGet();
            LOG.log(System.Logger.Level.WARNING, "skipped {0}-{1} offset {2}: {3}", record.topic(),
                    record.partition(), record.offset(), e.getMessage());
            return true;
        }
        for (Consumer<TransactionEvent> handler : handlers) {
            try {
                handler.accept(event);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "handler failed for event {0} at {1}-{2} offset {3}, will "
                        + "retry: {4}", event.eventId(), record.topic(), record.partition(), record.offset(),
                        e.getClass().getName());
                return false;
            }
        }
        taken.incrementAndGet();
        return true;
    }

    /** Every partition of the batch back to its first record not taken. */
    private void rewind(ConsumerRecords<String, String> records, Map<TopicPartition, OffsetAndMetadata> done) {
        for (TopicPartition partition : records.partitions()) {
            OffsetAndMetadata committed = done.get(partition);
            long first = committed != null ? committed.offset() : records.records(partition).getFirst().offset();
            consumer.seek(partition, first);
        }
    }

    private void commit(Map<TopicPartition, OffsetAndMetadata> done) {
        if (!done.isEmpty()) {
            consumer.commitSync(done);
        }
    }

    private void pause() {
        try {
            Thread.sleep(retryPause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    /** Stops {@link #run} after the record in hand; safe from any thread. */
    public void stop() {
        running.set(false);
        consumer.wakeup();
    }

    /** Events every handler has taken. */
    public long taken() {
        return taken.get();
    }

    /** Records skipped because they are not events. */
    public long invalid() {
        return invalid.get();
    }

    /** Times a handler failed and the stream went back. */
    public long retries() {
        return retries.get();
    }
}
