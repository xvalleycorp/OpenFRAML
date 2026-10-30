//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import vn.io.dxa.openframl.cases.CaseFile.Outcome;
import vn.io.dxa.openframl.cases.CaseFile.Status;
import vn.io.dxa.openframl.cases.CaseService.Investigator;
import vn.io.dxa.openframl.cases.CaseService.Role;
import vn.io.dxa.openframl.cases.memory.InMemoryCaseStore;

class CaseServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T03:00:00Z");
    private static final Investigator LAN = new Investigator("lan.analyst", Set.of(Role.ANALYST));
    private static final Investigator MINH = new Investigator("minh.analyst", Set.of(Role.ANALYST));
    private static final Investigator HA = new Investigator("ha.supervisor", Set.of(Role.SUPERVISOR));
    private static final Investigator BOTH = new Investigator("tu.both", Set.of(Role.ANALYST, Role.SUPERVISOR));

    private final InMemoryCaseStore store = new InMemoryCaseStore();
    private final CaseService cases = new CaseService(store, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Alert alert(String id, String subject) {
        return new Alert(id, "screening", "SANCTIONS_NAME_MATCH", subject, Optional.of("evt-" + id),
                Optional.of(0.93), Map.of("list", "UN-SC", "entry", "XAi.001"), NOW);
    }

    @Test
    void alertsAboutOneSubjectJoinOneCaseAndARetryChangesNothing() {
        CaseFile first = cases.raise(alert("a1", "cust-1"));
        CaseFile second = cases.raise(alert("a2", "cust-1"));
        CaseFile retried = cases.raise(alert("a2", "cust-1"));
        CaseFile other = cases.raise(alert("a3", "cust-2"));

        assertEquals(first.id(), second.id());
        assertEquals(2, retried.alerts().size());
        assertNotEquals(first.id(), other.id());
        assertEquals(Status.OPEN, second.status());
        assertEquals(List.of("OPENED", "ALERT_ADDED"), second.history().stream().map(CaseFile.Action::what).toList());
    }

    @Test
    void anAnalystProposesAndAnotherPersonApproves() {
        String id = cases.raise(alert("a1", "cust-1")).id();
        cases.assign(LAN, id, LAN);
        cases.note(LAN, id, "Birth year 1990 against 1975 on the list; ID numbers differ.");
        CaseFile proposed = cases.propose(LAN, id, Outcome.FALSE_POSITIVE, "A namesake: birth year and ID differ.");
        assertEquals(Status.AWAITING_APPROVAL, proposed.status());

        CaseFile closed = cases.approve(HA, id);
        assertEquals(Status.CLOSED, closed.status());
        assertEquals(Optional.of("ha.supervisor"), closed.conclusion().orElseThrow().approvedBy());
        assertEquals("lan.analyst", closed.conclusion().orElseThrow().proposedBy());
        assertEquals(List.of("OPENED", "ASSIGNED", "NOTE", "PROPOSED", "APPROVED"),
                closed.history().stream().map(CaseFile.Action::what).toList());

        CaseFile next = cases.raise(alert("a9", "cust-1"));
        assertNotEquals(id, next.id(), "a closed case takes no new alerts");
    }

    @Test
    void nobodyApprovesTheirOwnConclusion() {
        String id = cases.raise(alert("a1", "cust-1")).id();
        cases.assign(BOTH, id, BOTH);
        cases.propose(BOTH, id, Outcome.CONFIRMED, "Same passport number as the listed person.");

        assertThrows(SecurityException.class, () -> cases.approve(BOTH, id));
        assertThrows(SecurityException.class, () -> cases.approve(LAN, id), "not a supervisor");
        assertEquals(Status.CLOSED, cases.approve(HA, id).status());
    }

    @Test
    void onlyTheAssignedAnalystProposesAndOnlySupervisorsAssignOthers() {
        String id = cases.raise(alert("a1", "cust-1")).id();

        assertThrows(SecurityException.class, () -> cases.assign(LAN, id, MINH));
        assertThrows(IllegalArgumentException.class, () -> cases.assign(HA, id, HA), "not an analyst");
        cases.assign(HA, id, MINH);
        assertThrows(SecurityException.class, () -> cases.propose(LAN, id, Outcome.FALSE_POSITIVE, "x"));
        assertThrows(IllegalArgumentException.class, () -> cases.propose(MINH, id, Outcome.FALSE_POSITIVE, " "));
        assertThrows(IllegalStateException.class, () -> cases.approve(HA, id), "nothing proposed yet");
    }

    @Test
    void aReturnedOrOutdatedConclusionGoesBackToReview() {
        String id = cases.raise(alert("a1", "cust-1")).id();
        cases.assign(LAN, id, LAN);
        cases.propose(LAN, id, Outcome.FALSE_POSITIVE, "A namesake.");
        CaseFile returned = cases.returnToAnalyst(HA, id, "Check the second alias too.");
        assertEquals(Status.IN_REVIEW, returned.status());
        assertEquals(Optional.empty(), returned.proposal());

        cases.propose(LAN, id, Outcome.FALSE_POSITIVE, "Both aliases checked.");
        CaseFile reopened = cases.raise(alert("a2", "cust-1"));
        assertEquals(Status.IN_REVIEW, reopened.status(), "the conclusion did not see the new alert");
        assertEquals(Optional.empty(), reopened.proposal());
        assertThrows(IllegalStateException.class, () -> cases.approve(HA, id));
    }

    @Test
    void aStaleSaveIsRefused() {
        CaseFile c = cases.raise(alert("a1", "cust-1"));
        cases.assign(LAN, c.id(), LAN);

        assertThrows(ConcurrentModificationException.class, () -> store.save(c, c));
    }

    @Test
    void concurrentAlertsAboutOneSubjectOpenOneCase() throws Exception {
        List<Callable<CaseFile>> raises = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Alert alert = alert("c" + i, "cust-9");
            raises.add(() -> cases.raise(alert));
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            pool.invokeAll(raises).forEach(future -> {
                try {
                    future.get();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
        }
        CaseFile c = store.openCaseOf("cust-9").orElseThrow();
        assertEquals(50, c.alerts().size());
        assertTrue(store.withStatus(Status.OPEN).stream().filter(x -> x.subject().equals("cust-9")).count() == 1);
    }
}
