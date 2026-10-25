//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.valkey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import vn.io.dxa.openframl.gate.ApprovedPolicy;
import vn.io.dxa.openframl.gate.Gate;
import vn.io.dxa.openframl.gate.GatePolicy;
import vn.io.dxa.openframl.gate.PolicyAdmin;
import vn.io.dxa.openframl.gate.PolicyAdmin.Caller;
import vn.io.dxa.openframl.gate.PolicyAdmin.Role;
import vn.io.dxa.openframl.gate.PolicyChange;
import vn.io.dxa.openframl.gate.PolicyProposal;
import vn.io.dxa.openframl.gate.PolicySync;
import vn.io.dxa.openframl.gate.memory.InMemoryProfileStore;
import vn.io.dxa.openframl.gate.memory.InMemoryVelocityStore;
import vn.io.dxa.openframl.pack.CountryPack;
import vn.io.dxa.rulestone.core.DecisionTableJson;

/** Runs against a real Valkey, like {@link ValkeyStoresTest}. */
@EnabledIfEnvironmentVariable(named = "OPENFRAML_TEST_VALKEY", matches = ".+")
class ValkeyPolicyRepositoryTest {

    private static final Caller AN = new Caller("an.maker", Set.of(Role.MAKER));
    private static final Caller BINH = new Caller("binh.checker", Set.of(Role.CHECKER));

    private static Valkey valkey;

    private final CountryPack pack = CountryPack.forCountry("VN");
    private final GatePolicy initial = GatePolicy.of(pack);
    private final ValkeyPolicyRepository repository = new ValkeyPolicyRepository(valkey.connection(),
            new ValkeyLayout("ofr-test:" + UUID.randomUUID() + ":"));
    private final PolicyAdmin admin = new PolicyAdmin(pack, repository, initial, Clock.systemUTC());

    @BeforeAll
    static void connect() {
        valkey = Valkey.connect(System.getenv("OPENFRAML_TEST_VALKEY"), Duration.ofSeconds(2));
    }

    @AfterAll
    static void close() {
        if (valkey != null) {
            valkey.close();
        }
    }

    private List<String> tables(String threshold) {
        return initial.tables().stream()
                .map(table -> DecisionTableJson.write(table).replace("money(20000000, 'VND')", threshold)).toList();
    }

    private Gate gate() {
        return Gate.builder(pack).profiles(new InMemoryProfileStore()).velocity(new InMemoryVelocityStore()).build();
    }

    @Test
    void proposesApprovesAndSyncsANode() {
        PolicyProposal proposal = admin.propose(AN, tables("money(15000000, 'VND')"), "CHG-1");
        assertEquals(Optional.of(proposal), repository.proposal(proposal.id()));
        assertEquals(List.of(proposal), repository.pending());

        ApprovedPolicy approved = admin.approve(BINH, proposal.id(), proposal.toId());
        assertEquals(1, approved.sequence());
        assertEquals(List.of(), repository.pending());
        assertEquals(List.of(approved), repository.history());
        assertEquals(PolicyProposal.Status.APPROVED, repository.proposal(proposal.id()).orElseThrow().status());

        try (Gate gate = gate()) {
            new PolicySync(gate, pack, repository).sync();
            assertEquals(proposal.toId(), gate.policy().id());
        }
    }

    @Test
    void theRepositoryItselfRefusesWhatTheAdminWould() {
        PolicyProposal first = admin.propose(AN, tables("money(15000000, 'VND')"), "CHG-1");
        PolicyProposal second = admin.propose(AN, tables("money(12000000, 'VND')"), "CHG-2");

        assertThrows(IllegalArgumentException.class, () -> repository.approve(first.id(), "AN.MAKER", first.proposedAt()));
        assertThrows(NoSuchElementException.class, () -> repository.approve("nope", "binh", first.proposedAt()));
        repository.approve(first.id(), "binh.checker", first.proposedAt());
        assertThrows(IllegalStateException.class, () -> repository.approve(first.id(), "binh", first.proposedAt()));
        assertThrows(IllegalStateException.class, () -> repository.approve(second.id(), "binh", first.proposedAt()));

        PolicyProposal rejected = repository.reject(second.id(), "binh.checker", "stale", first.proposedAt());
        assertEquals(Optional.of("stale"), rejected.decisionReason());
    }

    @Test
    void ofManyApprovalsAtOnceOnlyOneWins() throws Exception {
        List<PolicyProposal> proposals = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            proposals.add(admin.propose(AN, tables("money(" + (11_000_000 + i) + ", 'VND')"), "CHG-" + i));
        }
        List<Callable<Boolean>> approvals = new ArrayList<>();
        for (PolicyProposal proposal : proposals) {
            approvals.add(() -> {
                try {
                    admin.approve(BINH, proposal.id(), proposal.toId());
                    return true;
                } catch (IllegalStateException e) {
                    return false;
                }
            });
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            long won = 0;
            for (Future<Boolean> result : pool.invokeAll(approvals)) {
                won += result.get() ? 1 : 0;
            }
            assertEquals(1, won);
        }
        assertEquals(1, repository.history().size());
    }

    @Test
    void aRollbackGoesBackToTheInitialTables() {
        PolicyProposal change = admin.propose(AN, tables("money(15000000, 'VND')"), "CHG-1");
        admin.approve(BINH, change.id(), change.toId());
        PolicyProposal back = admin.proposeRollback(AN, "INC-1");
        admin.approve(BINH, back.id(), back.toId());

        assertEquals(PolicyChange.Kind.ROLLBACK, repository.active().orElseThrow().change().kind());
        assertEquals(initial.id(), GatePolicy.compile(pack,
                repository.tables(initial.id()).stream().map(DecisionTableJson::read).toList()).id());
    }
}
