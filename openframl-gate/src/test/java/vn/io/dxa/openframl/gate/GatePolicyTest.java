//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.io.dxa.openframl.gate.memory.InMemoryProfileStore;
import vn.io.dxa.openframl.gate.memory.InMemoryVelocityStore;
import vn.io.dxa.openframl.model.AccountRef;
import vn.io.dxa.openframl.model.Amount;
import vn.io.dxa.openframl.model.Channel;
import vn.io.dxa.openframl.model.Decision;
import vn.io.dxa.openframl.model.TransactionEvent;
import vn.io.dxa.openframl.model.TransactionType;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionTableJson;

class GatePolicyTest {

    private static final AccountRef ALICE = new AccountRef("999901", "0123456789");
    private static final AccountRef STRANGER = new AccountRef("999902", "5555666677");
    private static final Instant NOW = Instant.parse("2026-10-25T03:00:00Z");
    private static final Duration BUDGET = Duration.ofSeconds(5);
    private static final Approval APPROVED = new Approval("an.maker", "binh.checker", "CHG-1024: lower the new "
            + "beneficiary threshold", NOW);

    private final TestPack pack = TestPack.standard();
    private final GatePolicy standard = GatePolicy.of(pack);
    /** The standard tables, with the new-beneficiary threshold lowered from 1,000 to 100 XTS. */
    private final GatePolicy strict = GatePolicy.compile(pack, tables(standard, "money(1000, 'XTS')",
            "money(100, 'XTS')"));
    private final List<PolicyChange> audited = new ArrayList<>();
    private Gate gate;

    @AfterEach
    void close() {
        if (gate != null) {
            gate.close();
        }
    }

    private Gate.Builder builder() {
        return Gate.builder(pack).velocity(new InMemoryVelocityStore()).policyAudit(audited::add)
                .profiles(new InMemoryProfileStore().account(ALICE, new AccountProfile(LocalDate.of(2020, 1, 1),
                        "LOW")));
    }

    private static TransactionEvent payment(String id, long amount) {
        return TransactionEvent.builder().eventId(id).occurredAt(NOW).channel(Channel.MOBILE)
                .type(TransactionType.TRANSFER_OUT).amount(Amount.of(amount, TestPack.CURRENCY)).customerId("alice")
                .originator(ALICE).beneficiary(STRANGER).build();
    }

    private static List<DecisionTable> tables(GatePolicy policy, String from, String to) {
        return policy.tables().stream()
                .map(table -> DecisionTableJson.read(DecisionTableJson.write(table).replace(from, to))).toList();
    }

    @Test
    void anActivatedPolicyDecidesFromTheNextPaymentAndIsAudited() {
        gate = builder().build();
        assertEquals(standard.id(), gate.policy().id());
        assertEquals(Decision.ALLOW, gate.decide(payment("evt-1", 500), BUDGET).decision());

        PolicyChange change = gate.activate(strict, APPROVED);

        assertEquals(new PolicyChange(PolicyChange.Kind.ACTIVATE, standard.id(), strict.id(), APPROVED), change);
        assertEquals(List.of(change), audited);
        GateOutcome outcome = gate.decide(payment("evt-2", 500), BUDGET);
        assertEquals(Decision.CHALLENGE, outcome.decision());
        assertEquals(List.of("NEW_BENEFICIARY_LARGE_AMOUNT"), outcome.reasonCodes());
    }

    @Test
    void rollbackGoesBackOneStep() {
        gate = builder().build();
        gate.activate(strict, APPROVED);
        PolicyChange back = gate.rollback(APPROVED);

        assertEquals(PolicyChange.Kind.ROLLBACK, back.kind());
        assertEquals(strict.id(), back.fromId());
        assertEquals(standard.id(), gate.policy().id());
        assertEquals(Decision.ALLOW, gate.decide(payment("evt-1", 500), BUDGET).decision());
        assertThrows(IllegalStateException.class, () -> gate.rollback(APPROVED));
        assertEquals(2, audited.size());
    }

    @Test
    void theCheckerMustNotBeTheMaker() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Approval("an.maker", " AN.MAKER ", "self-approved", NOW));
        assertEquals("the checker must not be the maker: an.maker", error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new Approval("an", "binh", " ", NOW));
    }

    @Test
    void aChangeTheAuditCannotRecordIsNotApplied() {
        gate = builder().policyAudit(change -> {
            throw new IllegalStateException("audit store down");
        }).build();
        assertThrows(IllegalStateException.class, () -> gate.activate(strict, APPROVED));
        assertEquals(standard.id(), gate.policy().id());
        assertEquals(Decision.ALLOW, gate.decide(payment("evt-1", 500), BUDGET).decision());
    }

    @Test
    void activatingThePolicyInForceIsRejected() {
        gate = builder().build();
        assertThrows(IllegalArgumentException.class, () -> gate.activate(GatePolicy.of(pack), APPROVED));
        assertEquals(List.of(), audited);
    }

    @Test
    void aPolicyThatDoesNotFitTheGateNeverCompiles() {
        IllegalStateException typo = assertThrows(IllegalStateException.class,
                () -> GatePolicy.compile(pack, tables(standard, "'CHALLENGE'", "'CHALENGE'")));
        assertTrue(typo.getMessage().contains("sets unknown action CHALENGE"), typo.getMessage());

        List<DecisionTable> withoutFallback = standard.tables().stream()
                .filter(table -> !table.id().equals(Gate.FALLBACK_ID)).toList();
        assertThrows(IllegalStateException.class, () -> GatePolicy.compile(pack, withoutFallback));
    }

    @Test
    void theIdDependsOnContentNotOrder() {
        List<DecisionTable> reversed = new ArrayList<>(standard.tables());
        java.util.Collections.reverse(reversed);
        assertEquals(standard.id(), GatePolicy.compile(pack, reversed).id());
        assertNotEquals(standard.id(), strict.id());
        assertTrue(standard.id().matches("sha256:[0-9a-f]{64}"), standard.id());
    }

    @Test
    void aDecisionInFlightFinishesOnThePolicyItStartedWith() throws Exception {
        CountDownLatch scoring = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        gate = builder().modelBudget(Duration.ofSeconds(4)).scoreProvider((event, profiles, velocity) -> {
            scoring.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Score("m1", BigDecimal.ZERO);
        }).build();

        CompletableFuture<GateOutcome> inFlight = CompletableFuture.supplyAsync(
                () -> gate.decide(payment("evt-1", 500), BUDGET));
        assertTrue(scoring.await(5, TimeUnit.SECONDS));
        gate.activate(strict, APPROVED);
        release.countDown();

        GateOutcome outcome = inFlight.get(5, TimeUnit.SECONDS);
        assertEquals(Decision.ALLOW, outcome.decision());
        assertTrue(outcome.policyVersion().startsWith("gate_decision@1@"), outcome.policyVersion());
    }

    @Test
    void aBankKeepsItsOwnTablesInADirectory(@TempDir Path directory) throws IOException {
        for (DecisionTable table : strict.tables()) {
            Files.writeString(directory.resolve(table.id() + ".v" + table.version() + ".stone.json"),
                    DecisionTableJson.write(table));
        }
        Files.writeString(directory.resolve("notes.txt"), "not a table");
        assertEquals(strict.id(), GatePolicy.fromDirectory(pack, directory).id());

        Path empty = Files.createDirectory(directory.resolve("empty"));
        assertThrows(IllegalArgumentException.class, () -> GatePolicy.fromDirectory(pack, empty));
    }
}
