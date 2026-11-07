//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.stream;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * Where the stream puts records it cannot take: records that are not events, and, if the stream is told to give up
 * on them, events a handler keeps failing on. A record put here is out of the way of the records after it, and kept
 * for someone to look at.
 */
public interface DeadLetters {

    /** No dead letters: invalid records are logged and skipped, and failing events are retried for ever. */
    DeadLetters NONE = null;

    /**
     * Keeps the record with the reason, before the stream commits past it.
     *
     * @throws RuntimeException if the record could not be kept; the stream then takes it again
     */
    void send(ConsumerRecord<String, String> record, String reason);

    /**
     * A Kafka topic of dead letters, e.g. {@code openframl.transactions.dead}: the record's key and value unchanged,
     * with the headers {@code openframl.reason} and {@code openframl.source} ({@code <topic>-<partition>@<offset>}).
     * Each send waits for the broker, so a record is kept before the stream commits past it. The topic holds what
     * the transaction topic holds, account numbers included, and is restricted like it.
     */
    static DeadLetters kafka(Producer<String, String> producer, String topic) {
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(topic, "topic");
        return (record, reason) -> {
            ProducerRecord<String, String> letter = new ProducerRecord<>(topic, record.key(), record.value());
            letter.headers().add("openframl.reason", reason.getBytes(StandardCharsets.UTF_8));
            letter.headers().add("openframl.source", (record.topic() + "-" + record.partition() + "@"
                    + record.offset()).getBytes(StandardCharsets.UTF_8));
            try {
                producer.send(letter).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted sending a dead letter", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("cannot send a dead letter to " + topic, e.getCause());
            }
        };
    }
}
