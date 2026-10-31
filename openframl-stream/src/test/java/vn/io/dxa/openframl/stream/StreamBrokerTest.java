//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import vn.io.dxa.openframl.cases.CaseFile;
import vn.io.dxa.openframl.cases.CaseService;
import vn.io.dxa.openframl.cases.memory.InMemoryCaseStore;
import vn.io.dxa.openframl.model.AccountRef;
import vn.io.dxa.openframl.model.Amount;
import vn.io.dxa.openframl.model.Channel;
import vn.io.dxa.openframl.model.TransactionEvent;
import vn.io.dxa.openframl.model.TransactionType;
import vn.io.dxa.openframl.monitor.MonitorPolicy;
import vn.io.dxa.openframl.monitor.TransactionMonitor;
import vn.io.dxa.openframl.monitor.memory.InMemoryActivityStore;
import vn.io.dxa.openframl.pack.CountryPack;
import vn.io.dxa.openframl.report.DailyReporting;
import vn.io.dxa.openframl.report.ExchangeRates;
import vn.io.dxa.openframl.report.ReportPolicy;
import vn.io.dxa.openframl.report.WorkingDays;

/** Runs against a real broker, e.g. {@code OPENFRAML_TEST_KAFKA=localhost:9092}; CI starts one. */
@EnabledIfEnvironmentVariable(named = "OPENFRAML_TEST_KAFKA", matches = ".+")
class StreamBrokerTest {

    private static final CountryPack VN = CountryPack.forCountry("VN");
    private static final String BOOTSTRAP = System.getenv("OPENFRAML_TEST_KAFKA");
    // Brokers refuse timestamps more than an hour ahead, so the day is three days ago
    private static final LocalDate DAY = LocalDate.now(VN.businessZone()).minusDays(3);
    private static final Instant DAY_START = DAY.atStartOfDay(VN.businessZone()).toInstant();

    private final String topic = "openframl-test-" + UUID.randomUUID();

    private TransactionEvent cash(String id, String customer, long vnd, Instant at) {
        return TransactionEvent.builder().eventId(id).occurredAt(at).channel(Channel.BRANCH)
                .type(TransactionType.CASH_DEPOSIT).amount(Amount.of(vnd, "VND")).customerId(customer)
                .originator(new AccountRef("999901", "000" + customer.hashCode())).build();
    }

    /** Publishes the events with their occurredAt as the record timestamp, keyed by account, to three partitions. */
    private void publish(List<TransactionEvent> events, Instant invalidAt) throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP))) {
            admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get();
        }
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP, ProducerConfig.ACKS_CONFIG, "all"),
                new StringSerializer(), new StringSerializer())) {
            for (TransactionEvent event : events) {
                producer.send(new ProducerRecord<>(topic, null, event.occurredAt().toEpochMilli(),
                        event.originator().key(), TransactionEventJson.write(event))).get();
            }
            producer.send(new ProducerRecord<>(topic, null, invalidAt.toEpochMilli(), "x", "not an event")).get();
        }
    }

    @Test
    void aClosedDayIsReadBackForReporting() throws Exception {
        List<TransactionEvent> events = new ArrayList<>();
        events.add(cash("before", "cust-1", 400_000_000, DAY_START.minus(Duration.ofDays(2))));
        for (int i = 0; i < 30; i++) {
            events.add(cash("day-" + i, "cust-" + (i % 3), 20_000_000,
                    DAY_START.plus(Duration.ofMinutes(30 + 40L * i))));
        }
        events.add(cash("after", "cust-1", 400_000_000, DAY_START.plus(Duration.ofDays(2).plusHours(10))));
        publish(events, DAY_START.plus(Duration.ofHours(10)));

        DailyReporting reporting = new DailyReporting(VN, ReportPolicy.of(VN), WorkingDays.mondayToFriday(Set.of()),
                ExchangeRates.none());
        DailyReporting.Run run;
        KafkaDayReader reader;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(StreamService.consumerConfig(
                Map.of("OPENFRAML_STREAM_KAFKA_BOOTSTRAP", BOOTSTRAP), null))) {
            reader = new KafkaDayReader(consumer, topic, VN.businessZone(), Duration.ofHours(1));
            run = reporting.run(DAY, reader.day(DAY));
        }

        assertEquals(30, run.transactions());
        assertEquals(0, run.otherDays(), "the margin is an hour; the other days are further away");
        assertEquals(1, reader.invalid());
        // Each customer deposited 10 x 20,000,000 VND = 200,000,000 VND: below the threshold
        assertEquals(List.of(), run.reports());
    }

    @Test
    void theStreamRaisesMonitoringAlertsInCases() throws Exception {
        Instant now = DAY_START.plus(Duration.ofHours(10));
        publish(List.of(cash("evt-1", "cust-1", 390_000_000, now),
                cash("evt-2", "cust-1", 380_000_000, now.plusSeconds(60))), now.plusSeconds(120));
        CaseService cases = new CaseService(new InMemoryCaseStore(), Clock.systemUTC());
        TransactionMonitor monitor = new TransactionMonitor(VN, MonitorPolicy.of(VN), new InMemoryActivityStore(),
                cases::raise, Duration.ofDays(7));
        TransactionStream stream = new TransactionStream(new KafkaConsumer<>(StreamService.consumerConfig(
                Map.of("OPENFRAML_STREAM_KAFKA_BOOTSTRAP", BOOTSTRAP), topic)), topic, List.of(monitor::process),
                Duration.ofMillis(100));
        Thread runner = Thread.ofPlatform().start(stream);
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (stream.taken() + stream.invalid() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        stream.stop();
        runner.join(Duration.ofSeconds(10));

        assertEquals(2, stream.taken());
        assertEquals(1, stream.invalid());
        CaseFile c = cases.withStatus(CaseFile.Status.OPEN).getFirst();
        assertEquals("cust-1", c.subject());
        assertEquals("CASH_STRUCTURING", c.alerts().getFirst().type());
    }
}
