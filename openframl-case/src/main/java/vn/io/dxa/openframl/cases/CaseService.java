//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.cases;

import java.time.Clock;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.io.dxa.openframl.cases.CaseFile.Action;
import vn.io.dxa.openframl.cases.CaseFile.Conclusion;
import vn.io.dxa.openframl.cases.CaseFile.Outcome;
import vn.io.dxa.openframl.cases.CaseFile.Status;

/**
 * Alerts in, approved conclusions out.
 *
 * <ul>
 *   <li>An alert joins the subject's case that is not closed, or opens one: an analyst sees everything about a
 *       subject at once, and a retried alert is not counted twice.</li>
 *   <li>An analyst takes a case (or a supervisor assigns it), investigates, and proposes a conclusion.</li>
 *   <li>A supervisor who did not propose it approves it, which closes the case, or returns it to the analyst.</li>
 *   <li>A new alert on a case awaiting approval sends it back to review: the conclusion did not see it.</li>
 * </ul>
 *
 * <p>Every action is in the case's history, with who did it and when.
 */
public final class CaseService {

    public enum Role {
        /** Investigates and proposes conclusions. */
        ANALYST,
        /** Assigns cases and approves or returns conclusions, never their own. */
        SUPERVISOR
    }

    /**
     * An authenticated person.
     *
     * @param name  their identity
     * @param roles what the bank allows them
     */
    public record Investigator(String name, Set<Role> roles) {

        public Investigator {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            name = name.strip();
            roles = Set.copyOf(roles);
        }
    }

    private static final String SYSTEM = "system";

    private final CaseStore store;
    private final Clock clock;

    public CaseService(CaseStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Adds the alert to the subject's open case, or opens one; an alert already in a case changes nothing. Retried
     * when another alert about the subject is added at the same moment.
     */
    public CaseFile raise(Alert alert) {
        for (int attempt = 0; ; attempt++) {
            try {
                return tryRaise(alert);
            } catch (ConcurrentModificationException e) {
                if (attempt == 49) {
                    throw e;
                }
            }
        }
    }

    private CaseFile tryRaise(Alert alert) {
        Optional<CaseFile> existing = store.caseOfAlert(alert.id());
        if (existing.isPresent()) {
            return existing.get();
        }
        Optional<CaseFile> open = store.openCaseOf(alert.subject());
        if (open.isEmpty()) {
            CaseFile created = new CaseFile(UUID.randomUUID().toString(), alert.subject(), List.of(alert),
                    Status.OPEN, Optional.empty(), Optional.empty(), Optional.empty(),
                    List.of(action(SYSTEM, "OPENED", alert.id())), clock.instant());
            store.save(null, created);
            return created;
        }
        CaseFile c = open.get();
        List<Alert> alerts = new ArrayList<>(c.alerts());
        alerts.add(alert);
        List<Action> history = new ArrayList<>(c.history());
        history.add(action(SYSTEM, "ALERT_ADDED", alert.id()));
        Status status = c.status();
        Optional<Conclusion> proposal = c.proposal();
        if (status == Status.AWAITING_APPROVAL) {
            status = Status.IN_REVIEW;
            proposal = Optional.empty();
            history.add(action(SYSTEM, "RETURNED", "a new alert arrived after the conclusion was proposed"));
        }
        CaseFile next = new CaseFile(c.id(), c.subject(), alerts, status, c.assignee(), proposal, c.conclusion(),
                history, c.openedAt());
        store.save(c, next);
        return next;
    }

    /**
     * Gives the case to {@code analyst}. An analyst may take a case for themselves; a supervisor may assign it to
     * anyone with the analyst role.
     */
    public CaseFile assign(Investigator actor, String caseId, Investigator analyst) {
        CaseFile c = get(caseId);
        if (!analyst.roles().contains(Role.ANALYST)) {
            throw new IllegalArgumentException(analyst.name() + " is not an analyst");
        }
        boolean self = actor.name().equalsIgnoreCase(analyst.name()) && actor.roles().contains(Role.ANALYST);
        if (!self && !actor.roles().contains(Role.SUPERVISOR)) {
            throw new SecurityException(actor.name() + " may only take cases for themselves");
        }
        requireStatus(c, Status.OPEN, Status.IN_REVIEW);
        return save(c, Status.IN_REVIEW, Optional.of(analyst.name()), c.proposal(), c.conclusion(),
                action(actor.name(), "ASSIGNED", analyst.name()));
    }

    /** Adds a note to the history, e.g. what the analyst checked. */
    public CaseFile note(Investigator actor, String caseId, String text) {
        requireAnyRole(actor);
        requireText(text, "a note");
        CaseFile c = get(caseId);
        return save(c, c.status(), c.assignee(), c.proposal(), c.conclusion(), action(actor.name(), "NOTE", text));
    }

    /** The assigned analyst proposes how the case ends. */
    public CaseFile propose(Investigator actor, String caseId, Outcome outcome, String reason) {
        require(actor, Role.ANALYST);
        requireText(reason, "a reason");
        CaseFile c = get(caseId);
        requireStatus(c, Status.IN_REVIEW);
        if (!c.assignee().map(actor.name()::equalsIgnoreCase).orElse(false)) {
            throw new SecurityException("case " + caseId + " is assigned to " + c.assignee().orElse("nobody"));
        }
        Conclusion proposal = new Conclusion(outcome, reason.strip(), actor.name(), Optional.empty());
        return save(c, Status.AWAITING_APPROVAL, c.assignee(), Optional.of(proposal), c.conclusion(),
                action(actor.name(), "PROPOSED", outcome + ": " + reason.strip()));
    }

    /** A supervisor who did not propose the conclusion approves it, and the case closes. */
    public CaseFile approve(Investigator actor, String caseId) {
        require(actor, Role.SUPERVISOR);
        CaseFile c = get(caseId);
        requireStatus(c, Status.AWAITING_APPROVAL);
        Conclusion proposal = c.proposal().orElseThrow();
        if (proposal.proposedBy().equalsIgnoreCase(actor.name())) {
            throw new SecurityException(actor.name() + " proposed this conclusion and cannot also approve it");
        }
        Conclusion approved = new Conclusion(proposal.outcome(), proposal.reason(), proposal.proposedBy(),
                Optional.of(actor.name()));
        return save(c, Status.CLOSED, c.assignee(), Optional.empty(), Optional.of(approved),
                action(actor.name(), "APPROVED", proposal.outcome().name()));
    }

    /** A supervisor sends the conclusion back to the analyst, with the reason. */
    public CaseFile returnToAnalyst(Investigator actor, String caseId, String reason) {
        require(actor, Role.SUPERVISOR);
        requireText(reason, "a reason");
        CaseFile c = get(caseId);
        requireStatus(c, Status.AWAITING_APPROVAL);
        return save(c, Status.IN_REVIEW, c.assignee(), Optional.empty(), c.conclusion(),
                action(actor.name(), "RETURNED", reason.strip()));
    }

    public Optional<CaseFile> find(String caseId) {
        return store.find(caseId);
    }

    /** The work queue for a status, oldest first. */
    public List<CaseFile> withStatus(Status status) {
        return store.withStatus(status);
    }

    private CaseFile save(CaseFile c, Status status, Optional<String> assignee, Optional<Conclusion> proposal,
            Optional<Conclusion> conclusion, Action action) {
        List<Action> history = new ArrayList<>(c.history());
        history.add(action);
        CaseFile next = new CaseFile(c.id(), c.subject(), c.alerts(), status, assignee, proposal, conclusion, history,
                c.openedAt());
        store.save(c, next);
        return next;
    }

    private CaseFile get(String caseId) {
        return store.find(caseId).orElseThrow(() -> new NoSuchElementException("no case " + caseId));
    }

    private Action action(String actor, String what, String detail) {
        return new Action(clock.instant(), actor, what, detail);
    }

    private static void requireStatus(CaseFile c, Status... allowed) {
        for (Status status : allowed) {
            if (c.status() == status) {
                return;
            }
        }
        throw new IllegalStateException("case " + c.id() + " is " + c.status());
    }

    private static void require(Investigator actor, Role role) {
        if (!actor.roles().contains(role)) {
            throw new SecurityException(actor.name() + " is not a " + role.name().toLowerCase());
        }
    }

    private static void requireAnyRole(Investigator actor) {
        if (actor.roles().isEmpty()) {
            throw new SecurityException(actor.name() + " is neither an analyst nor a supervisor");
        }
    }

    private static void requireText(String text, String what) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(what + " is required");
        }
    }
}
