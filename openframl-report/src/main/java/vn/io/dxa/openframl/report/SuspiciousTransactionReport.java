//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.report;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import vn.io.dxa.openframl.cases.Alert;
import vn.io.dxa.openframl.cases.CaseFile;

/**
 * The content of a suspicious transaction report, from a case an analyst concluded as {@code REPORTED} and a
 * supervisor approved: who it is about, why, the alerts and their evidence, and the audit trail.
 *
 * <p>The alerts name transactions by event id and hold no account numbers; the bank's filing joins them with the
 * account and customer details from its own systems. The customer must never learn that a report was made: nothing
 * here is for a channel the customer sees.
 *
 * @param id          {@code str:<caseId>}
 * @param caseId      the case
 * @param subject     the customer
 * @param conclusion  the approved conclusion, with the analyst's reason
 * @param concludedAt when the supervisor approved it: the bank knew of the suspicion by then at the latest
 * @param alerts      the alerts, oldest first
 * @param history     the case's audit trail
 */
public record SuspiciousTransactionReport(
        String id,
        String caseId,
        String subject,
        CaseFile.Conclusion conclusion,
        Instant concludedAt,
        List<Alert> alerts,
        List<CaseFile.Action> history) {

    public SuspiciousTransactionReport {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(conclusion, "conclusion");
        Objects.requireNonNull(concludedAt, "concludedAt");
        alerts = List.copyOf(alerts);
        history = List.copyOf(history);
    }

    /**
     * @throws IllegalArgumentException if the case is not closed as {@code REPORTED} with an approval
     */
    public static SuspiciousTransactionReport of(CaseFile c) {
        CaseFile.Conclusion conclusion = c.conclusion()
                .filter(it -> c.status() == CaseFile.Status.CLOSED)
                .filter(it -> it.outcome() == CaseFile.Outcome.REPORTED && it.approvedBy().isPresent())
                .orElseThrow(() -> new IllegalArgumentException("case " + c.id()
                        + " is not closed as REPORTED with an approval"));
        Instant concludedAt = c.history().reversed().stream().filter(action -> action.what().equals("APPROVED"))
                .map(CaseFile.Action::at).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("case " + c.id() + " has no approval in its history"));
        return new SuspiciousTransactionReport("str:" + c.id(), c.id(), c.subject(), conclusion, concludedAt,
                c.alerts(), c.history());
    }

    /** A report for each of these cases that is closed as {@code REPORTED}, oldest conclusion first. */
    public static List<SuspiciousTransactionReport> fromCases(Collection<CaseFile> cases) {
        return cases.stream()
                .filter(c -> c.status() == CaseFile.Status.CLOSED)
                .filter(c -> c.conclusion().map(it -> it.outcome() == CaseFile.Outcome.REPORTED).orElse(false))
                .map(SuspiciousTransactionReport::of)
                .sorted(Comparator.comparing(SuspiciousTransactionReport::concludedAt))
                .toList();
    }
}
