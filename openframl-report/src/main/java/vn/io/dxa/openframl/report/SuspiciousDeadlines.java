//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import vn.io.dxa.openframl.cases.Alert;
import vn.io.dxa.openframl.cases.CaseFile;
import vn.io.dxa.openframl.pack.CountryPack;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;

/**
 * When a suspicious transaction report is due, from the country pack's {@value ReportPolicy#SUSPICIOUS_ID} table,
 * in force on the day of the case's first alert. Two deadlines are given:
 *
 * <ul>
 *   <li>from the transaction: {@code fromTransaction} working days after the first alert's transaction, known as
 *       soon as a case opens, so a work queue can be ordered by it;</li>
 *   <li>from detection: {@code fromDetection} working days after the bank detected the suspicion. When that is, is
 *       the bank's reading of its law, chosen with {@link Detection}.</li>
 * </ul>
 *
 * <p>Investigation takes time, so a case concluded after the deadline from the transaction is flagged: the bank
 * sees which reports were late against it, whichever reading it applies.
 */
public final class SuspiciousDeadlines {

    /** When the bank counts the suspicion as detected. */
    public enum Detection {
        /** When a supervisor approves the conclusion that it must be reported. */
        APPROVAL,
        /** When the case's first alert was raised: the strictest reading. */
        FIRST_ALERT
    }

    /**
     * The deadlines of one case.
     *
     * @param transactionDate     the business date of the first alert's transaction
     * @param dueFromTransaction  the last day counted from the transaction
     * @param detectedOn          the business date of detection, once known
     * @param dueFromDetection    the last day counted from detection, once known
     * @param table               the table version that set them
     */
    public record Deadline(LocalDate transactionDate, LocalDate dueFromTransaction, Optional<LocalDate> detectedOn,
            Optional<LocalDate> dueFromDetection, String table) {

        public Deadline {
            Objects.requireNonNull(transactionDate, "transactionDate");
            Objects.requireNonNull(dueFromTransaction, "dueFromTransaction");
            Objects.requireNonNull(detectedOn, "detectedOn");
            Objects.requireNonNull(dueFromDetection, "dueFromDetection");
            Objects.requireNonNull(table, "table");
        }

        /** The earlier of the two deadlines that are known. */
        public LocalDate dueBy() {
            return dueFromDetection.filter(due -> due.isBefore(dueFromTransaction)).orElse(dueFromTransaction);
        }
    }

    private final CountryPack pack;
    private final ReportPolicy policy;
    private final WorkingDays workingDays;
    private final Detection detection;

    /**
     * @throws IllegalArgumentException if the policy has no {@value ReportPolicy#SUSPICIOUS_ID} table
     */
    public SuspiciousDeadlines(CountryPack pack, ReportPolicy policy, WorkingDays workingDays, Detection detection) {
        this.pack = Objects.requireNonNull(pack, "pack");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.workingDays = Objects.requireNonNull(workingDays, "workingDays");
        this.detection = Objects.requireNonNull(detection, "detection");
        if (!policy.has(ReportPolicy.SUSPICIOUS_ID)) {
            throw new IllegalArgumentException("the policy has no table " + ReportPolicy.SUSPICIOUS_ID);
        }
    }

    /**
     * The deadlines of a case, open or closed; detection is known once a case is closed as reported, or from its
     * first alert with {@link Detection#FIRST_ALERT}.
     *
     * @throws IllegalArgumentException if the case has no alert
     */
    public Deadline of(CaseFile c) {
        Instant first = c.alerts().stream().map(Alert::raisedAt).min(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalArgumentException("case " + c.id() + " has no alert"));
        LocalDate transactionDate = date(first);
        DecisionResult result = policy.engine().evaluate(DecisionRef.of(ReportPolicy.SUSPICIOUS_ID)
                .asOf(transactionDate).correlationId("str:" + c.id()), Facts.empty());
        int fromTransaction = ((Number) result.output().get("fromTransaction")).intValue();
        int fromDetection = ((Number) result.output().get("fromDetection")).intValue();
        Optional<LocalDate> detectedOn = switch (detection) {
            case FIRST_ALERT -> Optional.of(transactionDate);
            case APPROVAL -> approval(c).map(this::date);
        };
        return new Deadline(transactionDate, workingDays.plus(transactionDate, fromTransaction), detectedOn,
                detectedOn.map(day -> workingDays.plus(day, fromDetection)),
                result.decisionId() + "@" + result.ruleVersion());
    }

    /** Whether the case was concluded as reported after the deadline counted from the transaction. */
    public boolean concludedLate(CaseFile c) {
        Deadline deadline = of(c);
        return approval(c).map(this::date).map(day -> day.isAfter(deadline.dueFromTransaction())).orElse(false);
    }

    private static Optional<Instant> approval(CaseFile c) {
        boolean reported = c.status() == CaseFile.Status.CLOSED
                && c.conclusion().map(it -> it.outcome() == CaseFile.Outcome.REPORTED).orElse(false);
        if (!reported) {
            return Optional.empty();
        }
        return c.history().reversed().stream().filter(action -> action.what().equals("APPROVED"))
                .map(CaseFile.Action::at).findFirst();
    }

    private LocalDate date(Instant instant) {
        return LocalDate.ofInstant(instant, pack.businessZone());
    }
}
