//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vn.io.dxa.openframl.gate.memory.InMemoryProfileStore;
import vn.io.dxa.openframl.gate.memory.InMemoryVelocityStore;
import vn.io.dxa.openframl.gate.memory.InMemoryWatchlist;
import vn.io.dxa.openframl.model.AccountRef;
import vn.io.dxa.openframl.model.Amount;
import vn.io.dxa.openframl.model.Channel;
import vn.io.dxa.openframl.model.Decision;
import vn.io.dxa.openframl.model.SessionSignals;
import vn.io.dxa.openframl.model.TransactionEvent;
import vn.io.dxa.openframl.model.TransactionType;
import vn.io.dxa.rulestone.core.DecisionRecordJson;

class GateTest {

    private static final AccountRef ALICE = new AccountRef("999901", "0123456789");
    private static final AccountRef BOB = new AccountRef("999902", "9876543210");
    private static final AccountRef MULE = new AccountRef("999902", "5555666677");
    private static final Instant NOW = Instant.parse("2026-10-25T03:00:00Z");
    private static final Duration BUDGET = Duration.ofSeconds(1);

    private final InMemoryProfileStore profiles = new InMemoryProfileStore()
            .account(ALICE, new AccountProfile(LocalDate.of(2020, 1, 15), "LOW"))
            .knownBeneficiary(ALICE, BOB)
            .knownDevice("alice", "phone-1");
    private final InMemoryWatchlist watchlist = new InMemoryWatchlist();
    private final List<GateRecord> records = new ArrayList<>();
    private Gate gate;

    @AfterEach
    void close() {
        if (gate != null) {
            gate.close();
        }
    }

    private Gate.Builder builder() {
        return Gate.builder(TestPack.standard()).profiles(profiles).velocity(new InMemoryVelocityStore())
                .watchlist(watchlist).recordSink(records::add);
    }

    private static TransactionEvent payment(String id, AccountRef to, long amount, Instant at) {
        return TransactionEvent.builder().eventId(id).occurredAt(at).channel(Channel.MOBILE)
                .type(TransactionType.TRANSFER_OUT).amount(Amount.of(amount, TestPack.CURRENCY)).customerId("alice")
                .originator(ALICE).beneficiary(to)
                .session(new SessionSignals("phone-1", "192.0.2.10", false, false, "BIOMETRIC")).build();
    }

    @Test
    void allowsAnOrdinaryPaymentWithTheVersionThatDecidedIt() {
        gate = builder().build();
        GateOutcome outcome = gate.decide(payment("evt-1", BOB, 100, NOW), BUDGET);

        assertEquals(Decision.ALLOW, outcome.decision());
        assertEquals(List.of(), outcome.reasonCodes());
        assertTrue(outcome.policyVersion().matches("gate_decision@1@sha256:[0-9a-f]{64}"), outcome.policyVersion());
        assertEquals(Optional.empty(), outcome.fallback());

        GateRecord record = records.getFirst();
        assertEquals("evt-1", record.eventId());
        assertEquals(outcome, record.outcome());
        assertEquals(List.of("watchlist", "profiles", "velocity", "rules"), List.copyOf(record.stepNanos().keySet()));
        assertEquals(1, record.rules().size());
    }

    @Test
    void theMostSevereActionWinsAndEveryReasonIsKept() {
        gate = builder().scoreProvider((event, p, v) -> new Score("fraud-xgb-3", new BigDecimal("0.95"))).build();
        GateOutcome outcome = gate.decide(payment("evt-1", MULE, 5_000, NOW), BUDGET);

        assertEquals(Decision.DENY, outcome.decision());
        assertEquals(List.of("NEW_BENEFICIARY_LARGE_AMOUNT", "FRAUD_SCORE_HIGH"), outcome.reasonCodes());
        assertEquals(Optional.of("fraud-xgb-3"), records.getFirst().modelVersion());
        assertEquals(Optional.of(new BigDecimal("0.95")), records.getFirst().score());
    }

    @Test
    void theNextPaymentSeesThePreviousOne() {
        gate = builder().build();
        assertEquals(Decision.ALLOW, gate.decide(payment("evt-1", BOB, 100, NOW), BUDGET).decision());
        assertEquals(Decision.ALLOW, gate.decide(payment("evt-2", BOB, 100, NOW.plusSeconds(5)), BUDGET).decision());
        GateOutcome third = gate.decide(payment("evt-3", BOB, 100, NOW.plusSeconds(9)), BUDGET);

        assertEquals(Decision.CHALLENGE, third.decision());
        assertEquals(List.of("RAPID_SUCCESSION"), third.reasonCodes());
    }

    @Test
    void aListedBeneficiaryIsHeldEvenWhenTheStoresAreDown() {
        watchlist.replace(Map.of(MULE, new WatchlistHit("REGULATOR", "2026-10-24.1")));
        gate = builder().build();
        assertEquals(List.of("BENEFICIARY_ON_WATCHLIST"),
                gate.decide(payment("evt-1", MULE, 100, NOW), BUDGET).reasonCodes());

        Gate degraded = Gate.builder(TestPack.standard()).watchlist(watchlist).recordSink(records::add)
                .profiles(event -> {
                    throw new IllegalStateException("store down");
                })
                .velocity(new InMemoryVelocityStore()).build();
        try (degraded) {
            GateOutcome outcome = degraded.decide(payment("evt-2", MULE, 100, NOW), BUDGET);
            assertEquals(Decision.HOLD, outcome.decision());
            assertEquals(List.of("GATE_FAIL_CLOSED"), outcome.reasonCodes());
            assertEquals(Optional.of(GateFailure.STORE_UNAVAILABLE), outcome.fallback());
            assertTrue(outcome.policyVersion().startsWith("gate_fallback@1@sha256:"), outcome.policyVersion());
            assertEquals(Optional.of(new WatchlistHit("REGULATOR", "2026-10-24.1")), records.getLast().watchlist());
        }
    }

    @Test
    void aLateScoreIsDroppedAndTheRulesDecideAlone() {
        gate = builder().modelBudget(Duration.ofMillis(5)).scoreProvider((event, p, v) -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Score("slow-1", BigDecimal.ONE);
        }).build();
        GateOutcome outcome = gate.decide(payment("evt-1", BOB, 100, NOW), BUDGET);

        assertEquals(Decision.ALLOW, outcome.decision());
        assertEquals(Optional.empty(), outcome.fallback());
        assertEquals(Optional.empty(), records.getFirst().score());
        assertTrue(records.getFirst().stepNanos().get("model") < Duration.ofMillis(500).toNanos());
    }

    @Test
    void aFailingModelDoesNotFailTheDecision() {
        gate = builder().scoreProvider((event, p, v) -> {
            throw new IllegalStateException("model not loaded");
        }).build();
        GateOutcome outcome = gate.decide(payment("evt-1", BOB, 100, NOW), BUDGET);

        assertEquals(Decision.ALLOW, outcome.decision());
        assertEquals(Optional.empty(), outcome.fallback());
    }

    @Test
    void pastTheDeadlineTheFallbackPolicyDecides() {
        AtomicLong clock = new AtomicLong();
        // Every reading of the clock advances it by 4 ms; the budget runs out after the profile lookup.
        gate = builder().nanoTime(() -> clock.getAndAdd(Duration.ofMillis(4).toNanos())).build();
        GateOutcome small = gate.decide(payment("evt-1", BOB, 100, NOW), Duration.ofMillis(10));

        assertEquals(Decision.ALLOW, small.decision());
        assertEquals(Optional.of(GateFailure.TIMEOUT), small.fallback());
        assertEquals(List.of("GATE_FAIL_OPEN"), small.reasonCodes());
        assertTrue(small.reviewLater());

        GateOutcome large = gate.decide(payment("evt-2", BOB, 900, NOW), Duration.ofMillis(10));
        assertEquals(Decision.CHALLENGE, large.decision());
        assertFalse(large.reviewLater());
    }

    @Test
    void aFailingDecisionTableFallsBack() {
        gate = builder().build();
        // The table compares the amount with money in XTS; a payment in another currency fails the evaluation.
        TransactionEvent euros = TransactionEvent.builder().eventId("evt-1").occurredAt(NOW).channel(Channel.MOBILE)
                .type(TransactionType.TRANSFER_OUT).amount(Amount.of(5_000, "EUR")).customerId("alice")
                .originator(ALICE).beneficiary(MULE).build();
        GateOutcome outcome = gate.decide(euros, BUDGET);

        assertEquals(Optional.of(GateFailure.POLICY_ERROR), outcome.fallback());
        assertEquals(Decision.CHALLENGE, outcome.decision());
        assertEquals(List.of("GATE_DEGRADED"), outcome.reasonCodes());
    }

    @Test
    void whenTheFallbackTableFailsTooTheBuiltInLastResortDecides() {
        gate = Gate.builder(new TestPack("gate_decision.v1", "gate_fallback_broken.v1")).profiles(event -> {
            throw new IllegalStateException("store down");
        }).velocity(new InMemoryVelocityStore()).watchlist(watchlist).build();
        GateOutcome outcome = gate.decide(payment("evt-1", BOB, 100, NOW), BUDGET);
        assertEquals(Decision.CHALLENGE, outcome.decision());
        assertEquals(List.of(Gate.FALLBACK_FAILED), outcome.reasonCodes());
        assertEquals("built-in", outcome.policyVersion());

        watchlist.replace(Map.of(MULE, new WatchlistHit("BANK", "7")));
        assertEquals(Decision.HOLD, gate.decide(payment("evt-2", MULE, 100, NOW), BUDGET).decision());
    }

    @Test
    void aFailingRecordSinkDoesNotChangeTheDecision() {
        gate = builder().recordSink(record -> {
            throw new IllegalStateException("broker down");
        }).build();
        assertEquals(Decision.ALLOW, gate.decide(payment("evt-1", BOB, 100, NOW), BUDGET).decision());
    }

    @Test
    void recordsCarryNoAccountNumbers() {
        watchlist.replace(Map.of(MULE, new WatchlistHit("REGULATOR", "1")));
        gate = builder().build();
        gate.decide(payment("evt-1", MULE, 5_000, NOW), BUDGET);

        GateRecord record = records.getFirst();
        StringBuilder text = new StringBuilder(record.toString());
        record.rules().forEach(rule -> text.append(DecisionRecordJson.write(rule)));
        for (AccountRef account : List.of(ALICE, MULE)) {
            assertFalse(text.toString().contains(account.account()), text::toString);
        }
        assertFalse(text.toString().contains("5000"), text::toString);
    }

    @Test
    void tablesThatDoNotFitTheGateStopTheBuild() {
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> Gate.builder(new TestPack("gate_decision.v1")).profiles(profiles)
                        .velocity(new InMemoryVelocityStore()).build());
        assertEquals("the policy has no decision table gate_fallback", missing.getMessage());

        IllegalStateException typo = assertThrows(IllegalStateException.class,
                () -> Gate.builder(new TestPack("gate_decision_unknown_action.v1", "gate_fallback.v1"))
                        .profiles(profiles).velocity(new InMemoryVelocityStore()).build());
        assertTrue(typo.getMessage().contains("rule TYPO sets unknown action BLOCK"), typo.getMessage());

        assertThrows(IllegalStateException.class, () -> Gate.builder(TestPack.standard()).profiles(profiles).build());
    }

    private static TransactionEvent cash(String id, String customer, long amount, String purpose) {
        return TransactionEvent.builder().eventId(id).occurredAt(NOW).channel(Channel.BRANCH)
                .type(TransactionType.CASH_DEPOSIT).amount(Amount.of(amount, TestPack.CURRENCY)).customerId(customer)
                .originator(new AccountRef("999901", "TELLER-1")).purpose(purpose).build();
    }

    private Gate identifyingGate(String identificationTable) {
        return Gate.builder(new TestPack("gate_decision.v1", "gate_fallback.v1", identificationTable))
                .profiles(profiles).velocity(new InMemoryVelocityStore()).watchlist(watchlist)
                .recordSink(records::add).build();
    }

    @Test
    void aCustomerWithoutAnAccountIsIdentifiedOnceTheDayReachesTheThreshold() {
        profiles.customer("walk-in", new CustomerProfile(false, null));
        gate = identifyingGate("gate_identification.v1");

        GateOutcome first = gate.decide(cash("evt-1", "walk-in", 600, null), BUDGET);
        assertFalse(first.identifyCustomer());
        GateOutcome second = gate.decide(cash("evt-2", "walk-in", 600, null), BUDGET);
        assertTrue(second.identifyCustomer(), "600 + 600 reaches 1,000 over the day");
        assertEquals(List.of("IDENTIFY_CUSTOMER"), second.reasonCodes());
        assertEquals(Decision.ALLOW, second.decision(), "identification does not change the decision");
        assertEquals(List.of("watchlist", "profiles", "velocity", "rules", "identification"),
                List.copyOf(records.getLast().stepNanos().keySet()));
        assertEquals(2, records.getLast().rules().size());

        assertTrue(gate.decide(cash("evt-2", "walk-in", 600, null), BUDGET).identifyCustomer(),
                "a retried call is counted once and decided the same way");
        assertFalse(gate.decide(cash("evt-3", "walk-in", 600, "ACCOUNT_CLOSURE"), BUDGET).identifyCustomer());
    }

    @Test
    void aCustomerWithAnAccountIsNotIdentifiedByThisTable() {
        profiles.customer("alice", new CustomerProfile(true, LocalDate.of(2026, 10, 24)));
        gate = identifyingGate("gate_identification.v1");
        assertFalse(gate.decide(payment("evt-1", BOB, 5_000, NOW), BUDGET).identifyCustomer());
    }

    @Test
    void withoutTheTableNoIdentificationIsAsked() {
        gate = builder().build();
        assertFalse(gate.decide(cash("evt-1", "walk-in", 5_000, null), BUDGET).identifyCustomer());
    }

    @Test
    void theFallbackIsFollowedByIdentificationOnTheFactsGathered() {
        gate = Gate.builder(new TestPack("gate_decision.v1", "gate_fallback.v1", "gate_identification.v1"))
                .profiles(profiles).velocity(event -> {
                    throw new IllegalStateException("store down");
                }).watchlist(watchlist).build();
        GateOutcome outcome = gate.decide(cash("evt-1", "walk-in", 1_000, null), BUDGET);
        assertEquals(Optional.of(GateFailure.STORE_UNAVAILABLE), outcome.fallback());
        assertTrue(outcome.identifyCustomer(), "without the day's total, the transaction's own amount counts");
        assertTrue(outcome.reasonCodes().contains("IDENTIFY_CUSTOMER"), outcome.reasonCodes().toString());
    }

    @Test
    void whenTheIdentificationTableFailsTheCustomerIsIdentified() {
        gate = identifyingGate("gate_identification_broken.v1");
        GateOutcome outcome = gate.decide(payment("evt-1", BOB, 100, NOW), BUDGET);
        assertEquals(Decision.ALLOW, outcome.decision());
        assertTrue(outcome.identifyCustomer());
        assertEquals(List.of(Gate.IDENTIFICATION_FAILED), outcome.reasonCodes());
    }
}
