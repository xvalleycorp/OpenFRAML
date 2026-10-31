//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import vn.io.dxa.openframl.cases.Alert;
import vn.io.dxa.openframl.cases.CaseFile;
import vn.io.dxa.openframl.model.AccountRef;
import vn.io.dxa.openframl.model.Amount;
import vn.io.dxa.openframl.model.TransactionEvent;

/**
 * The open, versioned JSON form of the reports, from which a country pack or the bank fills the authority's own
 * forms. Field names are stable within a schema; a change that removes or renames a field raises the version.
 * Optional values that are absent are left out.
 *
 * <p>A transaction report holds full account numbers, as the authority needs them; report files are confidential.
 */
public final class ReportJson {

    /** The schema id of a {@link TransactionReport}. */
    public static final String TRANSACTION_SCHEMA = "openframl.report.transaction/1";

    /** The schema id of a {@link SuspiciousTransactionReport}. */
    public static final String SUSPICIOUS_SCHEMA = "openframl.report.suspicious/1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReportJson() {}

    public static ObjectNode toJson(TransactionReport report) {
        ObjectNode json = MAPPER.createObjectNode();
        json.put("schema", TRANSACTION_SCHEMA);
        json.put("id", report.id());
        json.put("kind", report.kind());
        json.put("subject", report.subject());
        json.put("businessDate", report.businessDate().toString());
        json.put("dueBy", report.dueBy().toString());
        ArrayNode tables = json.putArray("tables");
        report.tables().forEach(tables::add);
        ArrayNode transactions = json.putArray("transactions");
        for (TransactionEvent event : report.transactions()) {
            ObjectNode t = transactions.addObject();
            t.put("eventId", event.eventId());
            t.put("occurredAt", event.occurredAt().toString());
            t.put("type", event.type().name());
            t.put("channel", event.channel().name());
            amount(t.putObject("amount"), event.amount());
            account(t.putObject("account"), event.originator());
            if (event.beneficiary() != null) {
                account(t.putObject("counterparty"), event.beneficiary());
            }
        }
        return json;
    }

    public static ObjectNode toJson(SuspiciousTransactionReport report) {
        ObjectNode json = MAPPER.createObjectNode();
        json.put("schema", SUSPICIOUS_SCHEMA);
        json.put("id", report.id());
        json.put("caseId", report.caseId());
        json.put("subject", report.subject());
        CaseFile.Conclusion conclusion = report.conclusion();
        ObjectNode c = json.putObject("conclusion");
        c.put("outcome", conclusion.outcome().name());
        c.put("reason", conclusion.reason());
        c.put("proposedBy", conclusion.proposedBy());
        conclusion.approvedBy().ifPresent(name -> c.put("approvedBy", name));
        json.put("concludedAt", report.concludedAt().toString());
        ArrayNode alerts = json.putArray("alerts");
        for (Alert alert : report.alerts()) {
            ObjectNode a = alerts.addObject();
            a.put("id", alert.id());
            a.put("source", alert.source());
            a.put("type", alert.type());
            alert.eventId().ifPresent(id -> a.put("eventId", id));
            alert.score().ifPresent(score -> a.put("score", score));
            a.put("raisedAt", alert.raisedAt().toString());
            ObjectNode evidence = a.putObject("evidence");
            alert.evidence().forEach(evidence::put);
        }
        ArrayNode history = json.putArray("history");
        for (CaseFile.Action action : report.history()) {
            history.addObject().put("at", action.at().toString()).put("actor", action.actor())
                    .put("what", action.what()).put("detail", action.detail());
        }
        return json;
    }

    /** The report with its deadlines, under {@code deadline}. */
    public static ObjectNode toJson(SuspiciousTransactionReport report, SuspiciousDeadlines.Deadline deadline) {
        ObjectNode json = toJson(report);
        ObjectNode d = json.putObject("deadline");
        d.put("dueBy", deadline.dueBy().toString());
        d.put("transactionDate", deadline.transactionDate().toString());
        d.put("dueFromTransaction", deadline.dueFromTransaction().toString());
        deadline.detectedOn().ifPresent(day -> d.put("detectedOn", day.toString()));
        deadline.dueFromDetection().ifPresent(day -> d.put("dueFromDetection", day.toString()));
        d.put("table", deadline.table());
        return json;
    }

    /** The report as one line of JSON. */
    public static String write(TransactionReport report) {
        return toJson(report).toString();
    }

    /** The report as one line of JSON. */
    public static String write(SuspiciousTransactionReport report) {
        return toJson(report).toString();
    }

    private static void amount(ObjectNode json, Amount amount) {
        json.put("value", amount.value().toPlainString());
        json.put("currency", amount.currency());
    }

    private static void account(ObjectNode json, AccountRef account) {
        json.put("institution", account.institution());
        json.put("account", account.account());
    }
}
