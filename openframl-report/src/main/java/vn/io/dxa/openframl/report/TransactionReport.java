//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.report;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import vn.io.dxa.openframl.model.TransactionEvent;

/**
 * One customer's transactions of one business day that a threshold table says must be reported, e.g. a
 * large-value cash transaction report.
 *
 * <p>Unlike every other OpenFRAML output, a report holds full account numbers: the authority needs them. Report
 * files are confidential and kept as the law requires; {@link #toString()} still masks the account numbers.
 *
 * @param id           {@code <kind>:<subject>:<businessDate>}, so running a day again gives the same reports
 * @param kind         the report, the reason code of the rule that fired, e.g. {@code LARGE_CASH}
 * @param subject      the customer
 * @param businessDate the day of the transactions
 * @param dueBy        the last day to file it
 * @param tables       the table versions that put transactions in it, e.g. {@code report_daily@1@sha256:...}
 * @param transactions the transactions, oldest first
 */
public record TransactionReport(
        String id,
        String kind,
        String subject,
        LocalDate businessDate,
        LocalDate dueBy,
        List<String> tables,
        List<TransactionEvent> transactions) {

    public TransactionReport {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(dueBy, "dueBy");
        tables = List.copyOf(tables);
        transactions = List.copyOf(transactions);
    }
}
