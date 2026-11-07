//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.io.dxa.openframl.model.AccountRef;
import vn.io.dxa.openframl.model.Amount;
import vn.io.dxa.openframl.model.Channel;
import vn.io.dxa.openframl.model.TransactionEvent;
import vn.io.dxa.openframl.model.TransactionType;
import vn.io.dxa.openframl.pack.CountryPack;
import vn.io.dxa.rulestone.core.DecisionTableJson;

class ScenarioBacktestTest {

    private static final CountryPack VN = CountryPack.forCountry("VN");
    private static final Instant NOW = Instant.parse("2026-10-30T03:00:00Z");
    private static final Instant COUNT_FROM = NOW.minus(Duration.ofDays(1));

    private static TransactionEvent tx(String id, String account, TransactionType type, long amount, Instant at,
            String counterparty) {
        return TransactionEvent.builder().eventId(id).occurredAt(at).channel(Channel.INTERNET).type(type)
                .amount(Amount.of(amount, "VND")).customerId("cust-" + account)
                .originator(new AccountRef("999901", account)).beneficiary(new AccountRef("999902", counterparty))
                .build();
    }

    /** The pack's table with one threshold changed, effective only from a future date. */
    private static MonitorPolicy candidate(String from, String to) {
        String table = DecisionTableJson.write(MonitorPolicy.of(VN).tables().getFirst());
        String changed = table.replace(from, to).replace("2023-12-01", "2030-01-01");
        assertEquals(false, changed.equals(table.replace("2023-12-01", "2030-01-01")), "the threshold is in the table");
        return MonitorPolicy.compile(VN, List.of(DecisionTableJson.read(changed)));
    }

    private static List<TransactionEvent> passThrough(String account, long amount, Instant at) {
        return List.of(tx(account + "-in", account, TransactionType.TRANSFER_IN, amount, at, "1111"),
                tx(account + "-out", account, TransactionType.TRANSFER_OUT, amount, at.plusSeconds(60), "2222"));
    }

    @Test
    void aLowerThresholdShowsTheAlertsItAdds() {
        List<TransactionEvent> events = new ArrayList<>(passThrough("small", 60_000_000, NOW));
        events.addAll(passThrough("large", 150_000_000, NOW));

        ScenarioBacktest.Result result = ScenarioBacktest.run(VN, MonitorPolicy.of(VN),
                candidate("money(100000000, 'VND')", "money(50000000, 'VND')"), events, COUNT_FROM,
                Duration.ofDays(7));

        assertEquals(4, result.transactions());
        ScenarioBacktest.ScenarioChange change = result.scenarios().getFirst();
        assertEquals("PASS_THROUGH", change.scenario());
        assertEquals(1, change.current());
        assertEquals(2, change.candidate());
        assertEquals(1, change.added());
        assertEquals(0, change.removed());
        assertEquals(List.of("small-out"), change.addedSample());
    }

    @Test
    void aHigherThresholdShowsTheAlertsItRemoves() {
        ScenarioBacktest.Result result = ScenarioBacktest.run(VN, MonitorPolicy.of(VN),
                candidate("money(100000000, 'VND')", "money(200000000, 'VND')"),
                passThrough("large", 150_000_000, NOW), COUNT_FROM, Duration.ofDays(7));

        ScenarioBacktest.ScenarioChange change = result.scenarios().getFirst();
        assertEquals(1, change.current());
        assertEquals(0, change.candidate());
        assertEquals(1, change.removed());
        assertEquals(List.of("large-out"), change.removedSample());
    }

    @Test
    void historyBuildsTheWindowsButIsNotCounted() {
        List<TransactionEvent> events = new ArrayList<>(passThrough("old", 150_000_000, NOW.minus(Duration.ofDays(10))));
        // Money that came in before the period counts towards a pass-through inside it
        events.add(tx("in", "split", TransactionType.TRANSFER_IN, 150_000_000, COUNT_FROM.minusSeconds(60), "1111"));
        events.add(tx("out", "split", TransactionType.TRANSFER_OUT, 150_000_000, COUNT_FROM.plusSeconds(60), "2222"));
        events.add(events.getLast());

        ScenarioBacktest.Result result = ScenarioBacktest.run(VN, MonitorPolicy.of(VN),
                candidate("money(100000000, 'VND')", "money(90000000, 'VND')"), events, COUNT_FROM,
                Duration.ofDays(7));

        assertEquals(3, result.history());
        assertEquals(1, result.transactions(), "a transaction delivered twice is counted once");
        ScenarioBacktest.ScenarioChange change = result.scenarios().getFirst();
        assertEquals(1, change.current());
        assertEquals(1, change.candidate());
        assertEquals(0, change.added() + change.removed());
    }
}
