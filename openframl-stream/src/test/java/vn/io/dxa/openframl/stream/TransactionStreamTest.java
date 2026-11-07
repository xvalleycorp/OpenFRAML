//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import vn.io.dxa.openframl.model.AccountRef;
import vn.io.dxa.openframl.model.Amount;
import vn.io.dxa.openframl.model.Channel;
import vn.io.dxa.openframl.model.SessionSignals;
import vn.io.dxa.openframl.model.TransactionEvent;
import vn.io.dxa.openframl.model.TransactionType;

class TransactionStreamTest {

    private static final String TOPIC = "openframl.transactions";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    private static final AccountRef ACCOUNT = new AccountRef("999901", "0123456789");

    static TransactionEvent event(String id) {
        return TransactionEvent.builder().eventId(id).occurredAt(Instant.parse("2026-10-30T02:00:00Z"))
                .channel(Channel.MOBILE).type(TransactionType.TRANSFER_OUT).amount(Amount.of("1500000.50", "VND"))
                .customerId("cust-1").originator(ACCOUNT).beneficiary(new AccountRef("999902", "5555666677"))
                .session(new SessionSignals("phone-1", null, false, true, "BIOMETRIC"))
                .beneficiaryName("Nguyễn Văn Đức").counterpartyCountry("SG").build();
    }

    private final MockConsumer<String, String> consumer = new MockConsumer<>("earliest");
    private long offset;

    TransactionStreamTest() {
        consumer.assign(List.of(PARTITION));
        consumer.updateBeginningOffsets(Map.of(PARTITION, 0L));
    }

    private void publish(String value) {
        consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, offset++, ACCOUNT.key(), value));
    }

    private long committed() {
        var committed = consumer.committed(Set.of(PARTITION)).get(PARTITION);
        return committed == null ? 0 : committed.offset();
    }

    @Test
    void theJsonFormKeepsEveryField() {
        TransactionEvent event = event("evt-1");

        assertEquals(event, TransactionEventJson.read(TransactionEventJson.write(event)));
        TransactionEvent bare = TransactionEvent.builder().eventId("evt-2").occurredAt(event.occurredAt())
                .channel(Channel.BRANCH).type(TransactionType.CASH_DEPOSIT).amount(Amount.of(400_000_000, "VND"))
                .customerId("cust-1").originator(ACCOUNT).build();
        assertEquals(bare, TransactionEventJson.read(TransactionEventJson.write(bare)));
        assertFalse(TransactionEventJson.write(bare).contains("session"), "absent fields are left out");
    }

    @Test
    void anUnreadableEventNamesTheFieldButNeverTheAccount() {
        String good = TransactionEventJson.write(event("evt-1"));

        List<String> broken = List.of(
                good.replace("\"1500000.50\"", "1500000.50"),
                good.replace("\"institution\":\"999901\",", ""),
                good.replace("TRANSFER_OUT", "TELEPORT"),
                good.replace("openframl.transaction/1", "openframl.transaction/2"),
                good.replace("\"SG\"", "\"Singapore\""),
                "{not json");
        for (String message : broken) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> TransactionEventJson.read(message), message);
            assertFalse(e.getMessage().contains(ACCOUNT.account()), e.getMessage());
        }
    }

    @Test
    void eachEventGoesToEveryHandlerInOrderAndIsCommitted() {
        List<String> seen = new ArrayList<>();
        TransactionStream stream = new TransactionStream(consumer, TOPIC, List.of(
                e -> seen.add("screen " + e.eventId()), e -> seen.add("monitor " + e.eventId())), Duration.ZERO);
        publish(TransactionEventJson.write(event("evt-1")));
        publish("{\"schema\": \"something else\"}");
        publish(TransactionEventJson.write(event("evt-2")));

        stream.pollOnce(Duration.ZERO);

        assertEquals(List.of("screen evt-1", "monitor evt-1", "screen evt-2", "monitor evt-2"), seen);
        assertEquals(2, stream.taken());
        assertEquals(1, stream.invalid());
        assertEquals(3, committed());
    }

    @Test
    void aFailedHandlerTakesTheEventAgainAndNothingAfterIt() {
        List<String> seen = new ArrayList<>();
        boolean[] down = {true};
        TransactionStream stream = new TransactionStream(consumer, TOPIC, List.of(e -> {
            if (down[0] && e.eventId().equals("evt-2")) {
                throw new IllegalStateException("case store unreachable");
            }
            seen.add(e.eventId());
        }), Duration.ZERO);
        publish(TransactionEventJson.write(event("evt-1")));
        publish(TransactionEventJson.write(event("evt-2")));
        publish(TransactionEventJson.write(event("evt-3")));

        stream.pollOnce(Duration.ZERO);
        assertEquals(List.of("evt-1"), seen);
        assertEquals(1, committed(), "only what was taken is committed");
        assertEquals(1, stream.retries());
        assertEquals(1, consumer.position(PARTITION), "back to the event that failed");

        down[0] = false;
        // The mock does not replay records after a seek: the broker would; publish them again at their offsets
        offset = 1;
        publish(TransactionEventJson.write(event("evt-2")));
        publish(TransactionEventJson.write(event("evt-3")));
        stream.pollOnce(Duration.ZERO);

        assertEquals(List.of("evt-1", "evt-2", "evt-3"), seen);
        assertEquals(3, committed());
        assertEquals(3, stream.taken());
    }

    @Test
    void anEventAHandlerKeepsFailingOnGoesToTheDeadLettersAfterTheLimit() {
        List<String> letters = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        TransactionStream stream = new TransactionStream(consumer, TOPIC, List.of(e -> {
            if (e.eventId().equals("poison")) {
                throw new IllegalStateException("a bug the event triggers");
            }
            seen.add(e.eventId());
        }), Duration.ZERO, (record, reason) -> letters.add(record.offset() + " " + reason), 3);
        publish("{not json");
        String poison = TransactionEventJson.write(event("poison"));
        publish(poison);

        stream.pollOnce(Duration.ZERO);
        for (int attempt = 2; attempt <= 3; attempt++) {
            assertEquals(1, committed(), "the partition waits on the poison event");
            offset = 1;
            publish(poison);
            stream.pollOnce(Duration.ZERO);
        }
        publish(TransactionEventJson.write(event("evt-2")));
        stream.pollOnce(Duration.ZERO);

        assertEquals(List.of("0 not an event: not JSON, at line 1 column 2",
                "1 handler failed 3 times: java.lang.IllegalStateException"), letters);
        assertEquals(List.of("evt-2"), seen);
        assertEquals(1, stream.deadLettered());
        assertEquals(3, committed());
        assertEquals(Instant.parse("2026-10-30T02:00:00Z").toEpochMilli(), stream.lastEventAt());
    }

    @Test
    void aRecordIsNotSkippedUntilItIsKeptAsADeadLetter() {
        boolean[] down = {true};
        TransactionStream stream = new TransactionStream(consumer, TOPIC, List.of(e -> { }), Duration.ZERO,
                (record, reason) -> {
                    if (down[0]) {
                        throw new IllegalStateException("broker unreachable");
                    }
                }, 0);
        publish("not an event");

        stream.pollOnce(Duration.ZERO);
        assertEquals(0, committed());
        down[0] = false;
        offset = 0;
        publish("not an event");
        stream.pollOnce(Duration.ZERO);

        assertEquals(1, committed());
        assertEquals(1, stream.invalid());
    }

    @Test
    void theCountersAreServedToPrometheus() throws Exception {
        TransactionStream stream = new TransactionStream(consumer, TOPIC, List.of(e -> { }), Duration.ZERO);
        publish(TransactionEventJson.write(event("evt-1")));
        stream.pollOnce(Duration.ZERO);

        try (MetricsServer server = MetricsServer.start(0, stream::metrics)) {
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + server.port() + "/metrics")).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("openframl_stream_taken_total 1\n"), response.body());
            assertTrue(response.body().contains("openframl_stream_last_event_timestamp_seconds "
                    + Instant.parse("2026-10-30T02:00:00Z").toEpochMilli() / 1000.0 + "\n"), response.body());
            assertFalse(response.body().contains(ACCOUNT.account()));
        }
        assertThrows(IllegalArgumentException.class, () -> new TransactionStream(consumer, TOPIC, List.of(),
                Duration.ZERO, DeadLetters.NONE, 3), "a limit of attempts needs dead letters");
    }
}
