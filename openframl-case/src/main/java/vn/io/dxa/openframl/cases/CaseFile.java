//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.cases;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One investigation: every open alert about one subject, what was done, and how it ended. Immutable: each action
 * returns the next state of the case.
 *
 * @param id         the case's id
 * @param subject    who it is about
 * @param alerts     its alerts, oldest first
 * @param status     where it stands
 * @param assignee   the analyst working on it
 * @param proposal   the conclusion an analyst proposed, waiting for a supervisor
 * @param conclusion how it ended, once closed
 * @param history    every action, oldest first: the audit trail
 * @param openedAt   when the first alert opened it
 */
public record CaseFile(
        String id,
        String subject,
        List<Alert> alerts,
        Status status,
        Optional<String> assignee,
        Optional<Conclusion> proposal,
        Optional<Conclusion> conclusion,
        List<Action> history,
        Instant openedAt) {

    public enum Status {
        /** Waiting for an analyst. */
        OPEN,
        /** An analyst is investigating. */
        IN_REVIEW,
        /** An analyst proposed a conclusion; a supervisor must approve it. */
        AWAITING_APPROVAL,
        /** Concluded and approved. */
        CLOSED
    }

    public enum Outcome {
        /** The alerts were not what they seemed, e.g. a namesake. */
        FALSE_POSITIVE,
        /** Confirmed, and handled inside the bank (e.g. the account restricted). */
        CONFIRMED,
        /** Confirmed and reported to the authority (e.g. a suspicious transaction report). */
        REPORTED
    }

    /**
     * A conclusion, proposed by one person and approved by another.
     *
     * @param outcome    what the investigation found
     * @param reason     why, in the analyst's words
     * @param proposedBy the analyst
     * @param approvedBy the supervisor, once approved
     */
    public record Conclusion(Outcome outcome, String reason, String proposedBy, Optional<String> approvedBy) {

        public Conclusion {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(proposedBy, "proposedBy");
            Objects.requireNonNull(approvedBy, "approvedBy");
        }
    }

    /**
     * One entry of the audit trail.
     *
     * @param at     when
     * @param actor  who; {@code system} for alerts arriving
     * @param what   what was done, e.g. {@code ASSIGNED}, {@code ALERT_ADDED}, {@code APPROVED}
     * @param detail the note, the outcome, or the alert id
     */
    public record Action(Instant at, String actor, String what, String detail) {

        public Action {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(what, "what");
            detail = detail == null ? "" : detail;
        }
    }

    public CaseFile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(subject, "subject");
        alerts = List.copyOf(alerts);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(assignee, "assignee");
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(conclusion, "conclusion");
        history = List.copyOf(history);
        Objects.requireNonNull(openedAt, "openedAt");
    }

    /** Whether new alerts about the subject still join this case. */
    public boolean isOpen() {
        return status != Status.CLOSED;
    }
}
