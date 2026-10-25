//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vn.io.dxa.openframl.gate.PolicyAdmin.Caller;
import vn.io.dxa.openframl.gate.PolicyAdmin.Role;
import vn.io.dxa.openframl.gate.memory.InMemoryPolicyRepository;
import vn.io.dxa.openframl.gate.memory.InMemoryProfileStore;
import vn.io.dxa.openframl.gate.memory.InMemoryVelocityStore;
import vn.io.dxa.rulestone.core.DecisionTableJson;

class PolicyAdminTest {

    private static final Caller AN = new Caller("an.maker", Set.of(Role.MAKER));
    private static final Caller BINH = new Caller("binh.checker", Set.of(Role.CHECKER));
    private static final Caller CHI = new Caller("chi.both", Set.of(Role.MAKER, Role.CHECKER));

    private final TestPack pack = TestPack.standard();
    private final GatePolicy standard = GatePolicy.of(pack);
    private final InMemoryPolicyRepository repository = new InMemoryPolicyRepository();
    private final PolicyAdmin admin = new PolicyAdmin(pack, repository, standard,
            Clock.fixed(Instant.parse("2026-10-25T03:00:00Z"), ZoneOffset.UTC));
    private final List<PolicyChange> audited = new ArrayList<>();
    private final Gate node1 = gate();
    private final Gate node2 = gate();

    @AfterEach
    void close() {
        node1.close();
        node2.close();
    }

    private Gate gate() {
        return Gate.builder(pack).profiles(new InMemoryProfileStore()).velocity(new InMemoryVelocityStore())
                .policyAudit(audited::add).build();
    }

    private List<String> strictTables() {
        return standard.tables().stream()
                .map(table -> DecisionTableJson.write(table).replace("money(1000, 'XTS')", "money(100, 'XTS')"))
                .toList();
    }

    @Test
    void anApprovedPolicyReachesEveryNode() {
        PolicyProposal proposal = admin.propose(AN, strictTables(), "CHG-1024: lower the threshold");
        assertEquals(standard.id(), proposal.fromId());
        assertEquals(List.of(proposal), admin.pending());
        assertEquals(Optional.empty(), new PolicySync(node1, pack, repository).sync(), "nothing approved yet");

        ApprovedPolicy approved = admin.approve(BINH, proposal.id(), proposal.toId());
        assertEquals(1, approved.sequence());
        assertEquals("binh.checker", approved.change().approval().checker());
        assertEquals(List.of(), admin.pending());

        for (Gate node : List.of(node1, node2)) {
            PolicySync sync = new PolicySync(node, pack, repository);
            assertTrue(sync.sync().isPresent());
            assertEquals(proposal.toId(), node.policy().id());
            assertEquals(Optional.empty(), sync.sync(), "applied once");
        }
        assertEquals(2, audited.size());
    }

    @Test
    void theMakerCannotApproveTheirOwnChangeEvenWithBothRoles() {
        PolicyProposal proposal = admin.propose(CHI, strictTables(), "CHG-1");

        assertThrows(SecurityException.class, () -> admin.approve(CHI, proposal.id(), proposal.toId()));
        assertThrows(SecurityException.class, () -> admin.approve(
                new Caller("CHI.BOTH ", Set.of(Role.CHECKER)), proposal.id(), proposal.toId()));
        assertThrows(SecurityException.class, () -> admin.approve(AN, proposal.id(), proposal.toId()));
        assertThrows(SecurityException.class, () -> admin.propose(BINH, strictTables(), "CHG-2"));
        assertEquals(1, admin.pending().size());
    }

    @Test
    void theCheckerApprovesExactlyThePolicyTheyReviewed() {
        PolicyProposal proposal = admin.propose(AN, strictTables(), "CHG-1");

        assertEquals(strictTables(), admin.tables(proposal.toId()));
        assertThrows(IllegalArgumentException.class, () -> admin.approve(BINH, proposal.id(), standard.id()));
        assertThrows(NoSuchElementException.class, () -> admin.approve(BINH, "no-such-id", proposal.toId()));
    }

    @Test
    void tablesThatDoNotCompileAreNeverProposed() {
        List<String> broken = List.of(DecisionTableJson.write(standard.tables().getFirst()).replace("money(", "monee("));

        assertThrows(IllegalArgumentException.class, () -> admin.propose(AN, broken, "CHG-1"));
        assertThrows(IllegalArgumentException.class, () -> admin.propose(AN, strictTables(), " "));
        assertEquals(List.of(), admin.pending());
    }

    @Test
    void aSecondChangeMadeInParallelMustBeProposedAgain() {
        PolicyProposal first = admin.propose(AN, strictTables(), "CHG-1");
        List<String> other = standard.tables().stream()
                .map(table -> DecisionTableJson.write(table).replace("money(1000, 'XTS')", "money(500, 'XTS')"))
                .toList();
        PolicyProposal second = admin.propose(AN, other, "CHG-2");

        admin.approve(BINH, first.id(), first.toId());

        assertThrows(IllegalStateException.class, () -> admin.approve(BINH, second.id(), second.toId()));
        assertThrows(IllegalStateException.class, () -> admin.approve(BINH, first.id(), first.toId()));
    }

    @Test
    void aRollbackReturnsEveryNodeToTheInitialPolicy() {
        PolicyProposal change = admin.propose(AN, strictTables(), "CHG-1");
        admin.approve(BINH, change.id(), change.toId());
        new PolicySync(node1, pack, repository).sync();

        PolicyProposal back = admin.proposeRollback(AN, "INC-7: too many holds");
        assertEquals(PolicyChange.Kind.ROLLBACK, back.kind());
        assertEquals(standard.id(), back.toId());
        admin.approve(BINH, back.id(), back.toId());

        assertEquals(PolicyChange.Kind.ROLLBACK, new PolicySync(node1, pack, repository).sync().orElseThrow().kind());
        assertEquals(standard.id(), node1.policy().id());
        assertEquals(Optional.empty(), new PolicySync(node2, pack, repository).sync(), "node 2 never left it");
        assertEquals(2, admin.history().size());
    }

    @Test
    void aRejectedProposalCannotBeApproved() {
        PolicyProposal proposal = admin.propose(AN, strictTables(), "CHG-1");

        PolicyProposal rejected = admin.reject(BINH, proposal.id(), "threshold too low for salaries");
        assertEquals(PolicyProposal.Status.REJECTED, rejected.status());
        assertEquals(Optional.of("binh.checker"), rejected.decidedBy());
        assertThrows(IllegalStateException.class, () -> admin.approve(BINH, proposal.id(), proposal.toId()));
    }

    @Test
    void aNodeRefusesTablesChangedAfterApproval() {
        PolicyProposal proposal = admin.propose(AN, strictTables(), "CHG-1");
        admin.approve(BINH, proposal.id(), proposal.toId());
        repository.tamper(proposal.toId(), standard.tables().stream()
                .map(table -> DecisionTableJson.write(table).replace("money(1000, 'XTS')", "money(1, 'XTS')"))
                .toList());

        assertEquals(Optional.empty(), new PolicySync(node1, pack, repository).sync());
        assertEquals(standard.id(), node1.policy().id());
    }

    @Test
    void aNodeStartsOnTheApprovedPolicy() {
        PolicyProposal proposal = admin.propose(AN, strictTables(), "CHG-1");
        admin.approve(BINH, proposal.id(), proposal.toId());

        try (PolicySync sync = PolicySync.start(node1, pack, repository, java.time.Duration.ofHours(1))) {
            assertEquals(proposal.toId(), node1.policy().id());
        }
    }
}
