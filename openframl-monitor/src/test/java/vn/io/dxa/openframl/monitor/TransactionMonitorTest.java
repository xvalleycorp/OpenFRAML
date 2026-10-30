//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.io.dxa.openframl.cases.Alert;
import vn.io.dxa.openframl.cases.CaseFile;
import vn.io.dxa.openframl.cases.CaseService;
import vn.io.dxa.openframl.cases.memory.InMemoryCaseStore;
import vn.io.dxa.openframl.model.AccountRef;
import vn.io.dxa.openframl.model.Amount;
import vn.io.dxa.openframl.model.Channel;
import vn.io.dxa.openframl.model.TransactionEvent;
import vn.io.dxa.openframl.model.TransactionType;
import vn.io.dxa.openframl.monitor.memory.InMemoryActivityStore;
import vn.io.dxa.openframl.pack.CountryPack;
import vn.io.dxa.rulestone.core.DecisionTableJson;

/** The Vietnam pack's sample scenarios, end to end. */
class TransactionMonitorTest {

    private static final CountryPack VN = CountryPack.forCountry("VN");
    private static final AccountRef ACCOUNT = new AccountRef("999901", "0123456789");
    private static final Instant NOW = Instant.parse("2026-10-30T03:00:00Z");

    private final List<Alert> alerts = new ArrayList<>();
    private final TransactionMonitor monitor = new TransactionMonitor(VN, MonitorPolicy.of(VN),
            new InMemoryActivityStore(), alerts::add, Duration.ofDays(7));
    private int sequence;

    private TransactionEvent tx(TransactionType type, long amount, Instant at, String counterparty) {
        TransactionEvent.Builder event = TransactionEvent.builder().eventId("evt-" + ++sequence).occurredAt(at)
                .channel(type.name().startsWith("CASH") ? Channel.BRANCH : Channel.INTERNET).type(type)
                .amount(Amount.of(amount, "VND")).customerId("cust-1").originator(ACCOUNT);
        if (counterparty != null) {
            event.beneficiary(new AccountRef("999902", counterparty));
        }
        return event.build();
    }

    private List<String> scenarios(List<Alert> raised) {
        return raised.stream().map(Alert::type).toList();
    }

    @Test
    void cashSplitBelowTheReportingThresholdIsStructuring() {
        assertEquals(List.of(), monitor.process(tx(TransactionType.CASH_DEPOSIT, 390_000_000, NOW, null)));
        List<Alert> raised = monitor.process(tx(TransactionType.CASH_DEPOSIT, 380_000_000, NOW.plusSeconds(3_600),
                null));

        assertEquals(List.of("CASH_STRUCTURING"), scenarios(raised));
        Alert alert = raised.getFirst();
        assertEquals("cust-1", alert.subject());
        assertEquals("monitoring", alert.source());
        assertEquals("HIGH", alert.evidence().get("severity"));
        assertEquals("770000000 VND", alert.evidence().get("cashIn24h.amount"));
        assertTrue(alert.evidence().get("table").startsWith("monitor_scenarios@1"), alert.evidence()::toString);
        assertFalse(alert.evidence().toString().contains(ACCOUNT.account()), "no account number in the evidence");
    }

    @Test
    void moneyPassedOnWithin48Hours() {
        monitor.process(tx(TransactionType.TRANSFER_IN, 200_000_000, NOW, "1111"));
        assertEquals(List.of(), monitor.process(tx(TransactionType.TRANSFER_OUT, 100_000_000,
                NOW.plusSeconds(600), "2222")));
        List<Alert> raised = monitor.process(tx(TransactionType.TRANSFER_OUT, 90_000_000, NOW.plusSeconds(1_200),
                "3333"));

        assertEquals(List.of("PASS_THROUGH"), scenarios(raised));
        assertEquals("0.9500", raised.getFirst().evidence().get("flow48h.outToIn"));
    }

    @Test
    void manySendersInADay() {
        for (int i = 0; i < 9; i++) {
            assertEquals(List.of(), monitor.process(tx(TransactionType.TRANSFER_IN, 2_000_000,
                    NOW.plusSeconds(i * 600L), "sender-" + i)));
        }
        assertEquals(List.of("MANY_SENDERS"), scenarios(monitor.process(tx(TransactionType.TRANSFER_IN, 2_000_000,
                NOW.plusSeconds(6_000), "sender-9"))));
    }

    @Test
    void aDormantAccountWakesUp() {
        monitor.process(tx(TransactionType.TRANSFER_IN, 1_000_000, NOW.minus(Duration.ofDays(200)), "1111"));
        List<Alert> raised = monitor.process(tx(TransactionType.TRANSFER_IN, 60_000_000, NOW, "2222"));

        assertEquals(List.of("DORMANT_REACTIVATED"), scenarios(raised));
        assertEquals("200", raised.getFirst().evidence().get("account.daysSinceLastActivity"));
    }

    @Test
    void aTransactionDeliveredTwiceIsCountedOnce() {
        TransactionEvent first = tx(TransactionType.CASH_DEPOSIT, 390_000_000, NOW, null);
        monitor.process(first);
        assertEquals(List.of(), monitor.process(first), "the same deposit again is not a second deposit");
    }

    @Test
    void aScenarioThatKeepsFiringRaisesOneAlertPerPeriodIntoOneCase() {
        CaseService cases = new CaseService(new InMemoryCaseStore(), Clock.fixed(NOW, ZoneOffset.UTC));
        TransactionMonitor toCases = new TransactionMonitor(VN, MonitorPolicy.of(VN), new InMemoryActivityStore(),
                cases::raise, Duration.ofDays(7));
        for (int i = 0; i < 15; i++) {
            toCases.process(tx(TransactionType.TRANSFER_IN, 2_000_000, NOW.plusSeconds(i * 60L), "sender-" + i));
        }
        toCases.process(tx(TransactionType.TRANSFER_IN, 2_000_000, NOW.plus(Duration.ofDays(8)), "sender-a"));
        for (int i = 0; i < 10; i++) {
            toCases.process(tx(TransactionType.TRANSFER_IN, 2_000_000, NOW.plus(Duration.ofDays(8)).plusSeconds(i),
                    "late-" + i));
        }

        CaseFile c = cases.withStatus(CaseFile.Status.OPEN).getFirst();
        assertEquals(2, c.alerts().size(), "once on the first day, once more after the suppression period");
    }

    @Test
    void aPolicyWithoutSeverityIsNeverBuilt() {
        String table = DecisionTableJson.write(MonitorPolicy.of(VN).tables().getFirst())
                .replace("'HIGH'", "'URGENT'");

        assertThrows(IllegalStateException.class, () -> MonitorPolicy.compile(VN,
                List.of(DecisionTableJson.read(table))));
        assertThrows(IllegalStateException.class, () -> MonitorPolicy.compile(VN, List.of()));
    }
}
