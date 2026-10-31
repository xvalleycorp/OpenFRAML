//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.stream;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.TopicPartition;
import vn.io.dxa.openframl.model.TransactionEvent;

/**
 * Reads one business day of the transaction topic back, for Regulatory Reporting: the records whose Kafka timestamp
 * falls within the day, widened by a margin on each side, from every partition. The topic is the day's record, so no
 * other copy of the transactions is kept; its retention must cover the days a report may need to be run again.
 *
 * <p>Record timestamps are the producer's, not the transaction's {@code occurredAt}; the margin covers the
 * difference, and {@code DailyReporting} keeps only the events of the day. Records that are not events are skipped
 * and counted. The events are read as they are iterated, so a large day is never held in memory at once.
 */
public final class KafkaDayReader {

    private final Consumer<String, String> consumer;
    private final String topic;
    private final ZoneId zone;
    private final Duration margin;
    private long invalid;

    /**
     * @param consumer a consumer of string keys and values with no group, used by this reader only
     * @param zone     the zone of the business day, the country pack's
     * @param margin   how far before and after the day to read, e.g. one hour
     */
    public KafkaDayReader(Consumer<String, String> consumer, String topic, ZoneId zone, Duration margin) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.topic = Objects.requireNonNull(topic, "topic");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.margin = Objects.requireNonNull(margin, "margin");
    }

    /** The day's events, read once, as they are iterated. */
    public Iterable<TransactionEvent> day(LocalDate businessDate) {
        Instant from = businessDate.atStartOfDay(zone).toInstant().minus(margin);
        long to = businessDate.plusDays(1).atStartOfDay(zone).toInstant().plus(margin).toEpochMilli();
        List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                .map(info -> new TopicPartition(topic, info.partition())).toList();
        consumer.assign(partitions);
        Map<TopicPartition, Long> start = new HashMap<>();
        partitions.forEach(partition -> start.put(partition, from.toEpochMilli()));
        Map<TopicPartition, OffsetAndTimestamp> offsets = consumer.offsetsForTimes(start);
        Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
        Set<TopicPartition> active = new HashSet<>();
        for (TopicPartition partition : partitions) {
            OffsetAndTimestamp offset = offsets.get(partition);
            if (offset != null && offset.offset() < ends.get(partition)) {
                consumer.seek(partition, offset.offset());
                active.add(partition);
            }
        }
        consumer.pause(partitions.stream().filter(partition -> !active.contains(partition)).toList());
        return () -> new Events(active, ends, to);
    }

    /** Records skipped because they are not events, over every day read. */
    public long invalid() {
        return invalid;
    }

    private final class Events implements Iterator<TransactionEvent> {

        private final Set<TopicPartition> active;
        private final Map<TopicPartition, Long> ends;
        private final long to;
        private final Deque<TransactionEvent> buffer = new ArrayDeque<>();

        Events(Set<TopicPartition> active, Map<TopicPartition, Long> ends, long to) {
            this.active = active;
            this.ends = ends;
            this.to = to;
        }

        @Override
        public boolean hasNext() {
            while (buffer.isEmpty() && !active.isEmpty()) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    TopicPartition partition = new TopicPartition(record.topic(), record.partition());
                    if (!active.contains(partition)) {
                        continue;
                    }
                    if (record.timestamp() >= to) {
                        finish(partition);
                        continue;
                    }
                    try {
                        buffer.add(TransactionEventJson.read(record.value() == null ? "" : record.value()));
                    } catch (IllegalArgumentException e) {
                        invalid++;
                    }
                    if (record.offset() + 1 >= ends.get(partition)) {
                        finish(partition);
                    }
                }
                for (TopicPartition partition : List.copyOf(active)) {
                    if (consumer.position(partition) >= ends.get(partition)) {
                        finish(partition);
                    }
                }
            }
            return !buffer.isEmpty();
        }

        @Override
        public TransactionEvent next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return buffer.poll();
        }

        private void finish(TopicPartition partition) {
            active.remove(partition);
            consumer.pause(List.of(partition));
        }
    }
}
