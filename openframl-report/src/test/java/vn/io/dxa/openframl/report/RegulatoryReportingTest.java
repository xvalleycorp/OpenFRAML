//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vn.io.dxa.openframl.cases.Alert;
import vn.io.dxa.openframl.cases.CaseFile;
import vn.io.dxa.openframl.cases.CaseService;
import vn.io.dxa.openframl.cases.CaseService.Investigator;
import vn.io.dxa.openframl.cases.CaseService.Role;
import vn.io.dxa.openframl.cases.memory.InMemoryCaseStore;
import vn.io.dxa.openframl.model.AccountRef;
import vn.io.dxa.openframl.model.Amount;
import vn.io.dxa.openframl.model.Channel;
import vn.io.dxa.openframl.model.TransactionEvent;
import vn.io.dxa.openframl.model.TransactionType;
import vn.io.dxa.openframl.pack.CountryPack;
import vn.io.dxa.rulestone.core.DecisionTableJson;

/** The Vietnam pack's reporting thresholds, end to end. */
class RegulatoryReportingTest {

    private static final CountryPack VN = CountryPack.forCountry("VN");
    private static final AccountRef ACCOUNT = new AccountRef("999901", "0123456789");
    private static final AccountRef OTHER = new AccountRef("999902", "5555666677");
    // Friday 30 October 2026; the business day runs from 17:00 UTC the day before
    private static final LocalDate DAY = LocalDate.of(2026, 10, 30);
    private static final Instant MORNING = Instant.parse("2026-10-30T02:00:00Z");

    private final DailyReporting reporting = new DailyReporting(VN, ReportPolicy.of(VN),
            WorkingDays.mondayToFriday(Set.of()), ExchangeRates.none());
    private int sequence;

    private TransactionEvent tx(String customer, TransactionType type, Amount amount, Instant at) {
        TransactionEvent.Builder event = TransactionEvent.builder().eventId("evt-" + ++sequence).occurredAt(at)
                .channel(type.name().startsWith("CASH") ? Channel.BRANCH : Channel.INTERNET).type(type)
                .amount(amount).customerId(customer).originator(ACCOUNT);
        if (!type.name().startsWith("CASH")) {
            event.beneficiary(OTHER);
        }
        return event.build();
    }

    private TransactionEvent cash(String customer, TransactionType type, long vnd, Instant at) {
        return tx(customer, type, Amount.of(vnd, "VND"), at);
    }

    @Test
    void cashSplitOverTheDayIsReportedAsOne() {
        List<TransactionEvent> day = List.of(
                cash("cust-1", TransactionType.CASH_DEPOSIT, 150_000_000, MORNING),
                cash("cust-1", TransactionType.CASH_DEPOSIT, 150_000_000, MORNING.plusSeconds(3_600)),
                cash("cust-1", TransactionType.CASH_WITHDRAWAL, 100_000_000, MORNING.plusSeconds(7_200)),
                cash("cust-2", TransactionType.CASH_DEPOSIT, 399_999_999, MORNING));

        DailyReporting.Run run = reporting.run(DAY, day);

        assertEquals(1, run.reports().size(), run::toString);
        TransactionReport report = run.reports().getFirst();
        assertEquals("LARGE_CASH", report.kind());
        assertEquals("LARGE_CASH:cust-1:2026-10-30", report.id());
        assertEquals("cust-1", report.subject());
        assertEquals(List.of("evt-1", "evt-2", "evt-3"),
                report.transactions().stream().map(TransactionEvent::eventId).toList());
        assertEquals(1, report.tables().size());
        assertTrue(report.tables().getFirst().startsWith("report_daily@1@sha256:"), report.tables()::toString);
        assertEquals(LocalDate.of(2026, 11, 2), report.dueBy(), "one working day after a Friday is the Monday");
        assertFalse(report.toString().contains(ACCOUNT.account()), "toString masks account numbers");
    }

    @Test
    void theBusinessDayIsTheCountrysDayAndARunCanBeRepeated() {
        TransactionEvent lateTheDayBefore = cash("cust-1", TransactionType.CASH_DEPOSIT, 400_000_000,
                Instant.parse("2026-10-29T16:59:59Z"));
        TransactionEvent earlyThatDay = cash("cust-1", TransactionType.CASH_DEPOSIT, 400_000_000,
                Instant.parse("2026-10-29T17:00:00Z"));
        List<TransactionEvent> events = List.of(lateTheDayBefore, earlyThatDay, earlyThatDay);

        DailyReporting.Run first = reporting.run(DAY, events);
        DailyReporting.Run again = reporting.run(DAY, events);

        assertEquals(1, first.transactions());
        assertEquals(1, first.otherDays());
        assertEquals(1, first.duplicates());
        assertEquals(List.of(earlyThatDay), first.reports().getFirst().transactions());
        assertEquals(first.reports(), again.reports());
    }

    @Test
    void largeTransfersBothWaysAreReported() {
        List<TransactionEvent> day = List.of(
                tx("cust-1", TransactionType.TRANSFER_OUT, Amount.of(500_000_000, "VND"), MORNING),
                tx("cust-1", TransactionType.TRANSFER_OUT, Amount.of(499_999_999, "VND"), MORNING),
                tx("cust-1", TransactionType.CARD_PAYMENT, Amount.of(600_000_000, "VND"), MORNING),
                tx("cust-3", TransactionType.TRANSFER_IN, Amount.of(800_000_000, "VND"), MORNING));

        List<TransactionReport> reports = reporting.run(DAY, day).reports();

        assertEquals(List.of("ELECTRONIC_TRANSFER:cust-1:2026-10-30", "ELECTRONIC_TRANSFER:cust-3:2026-10-30"),
                reports.stream().map(TransactionReport::id).toList());
        assertEquals(List.of("evt-1"), reports.getFirst().transactions().stream().map(TransactionEvent::eventId)
                .toList());
    }

    @Test
    void transfersAcrossTheBorderHaveTheirOwnThreshold() {
        TransactionEvent dollarsAbroad = TransactionEvent.builder().eventId("evt-usd").occurredAt(MORNING)
                .channel(Channel.INTERNET).type(TransactionType.TRANSFER_OUT).amount(Amount.of(1_000, "USD"))
                .customerId("cust-1").originator(ACCOUNT).beneficiary(OTHER).counterpartyCountry("SG").build();
        TransactionEvent dongFromAbroad = TransactionEvent.builder().eventId("evt-vnd").occurredAt(MORNING)
                .channel(Channel.INTERNET).type(TransactionType.TRANSFER_IN).amount(Amount.of(30_000_000, "VND"))
                .customerId("cust-2").originator(ACCOUNT).beneficiary(OTHER).counterpartyCountry("JP").build();
        TransactionEvent dongAtHome = TransactionEvent.builder().eventId("evt-home").occurredAt(MORNING)
                .channel(Channel.INTERNET).type(TransactionType.TRANSFER_OUT).amount(Amount.of(30_000_000, "VND"))
                .customerId("cust-3").originator(ACCOUNT).beneficiary(OTHER).counterpartyCountry("VN").build();
        List<TransactionEvent> day = List.of(dollarsAbroad, dongFromAbroad, dongAtHome);

        // Without rates, only the dollar amount is known in dollars
        assertEquals(List.of("INTERNATIONAL_TRANSFER:cust-1:2026-10-30"),
                reporting.run(DAY, day).reports().stream().map(TransactionReport::id).toList());

        DailyReporting withRates = new DailyReporting(VN, ReportPolicy.of(VN), WorkingDays.mondayToFriday(Set.of()),
                (currency, date) -> currency.equals("USD") ? Optional.of(new BigDecimal("26300")) : Optional.empty());
        assertEquals(List.of("INTERNATIONAL_TRANSFER:cust-1:2026-10-30", "INTERNATIONAL_TRANSFER:cust-2:2026-10-30"),
                withRates.run(DAY, day).reports().stream().map(TransactionReport::id).toList());
    }

    @Test
    void foreignCurrencyIsComparedAtTheBanksRate() {
        List<TransactionEvent> day = List.of(
                tx("cust-1", TransactionType.TRANSFER_OUT, Amount.of(20_000, "USD"), MORNING),
                tx("cust-2", TransactionType.CASH_DEPOSIT, Amount.of(10_000, "EUR"), MORNING),
                tx("cust-2", TransactionType.CASH_DEPOSIT, Amount.of(200_000_000, "VND"), MORNING));

        DailyReporting.Run withoutRates = reporting.run(DAY, day);
        assertEquals(List.of(), withoutRates.reports());
        assertEquals(2, withoutRates.unconverted());

        Map<String, BigDecimal> rates = Map.of("USD", new BigDecimal("26300"), "EUR", new BigDecimal("30500"));
        DailyReporting withRates = new DailyReporting(VN, ReportPolicy.of(VN), WorkingDays.mondayToFriday(Set.of()),
                (currency, date) -> Optional.ofNullable(rates.get(currency)));
        DailyReporting.Run run = withRates.run(DAY, day);

        assertEquals(0, run.unconverted());
        assertEquals(List.of("ELECTRONIC_TRANSFER:cust-1:2026-10-30", "LARGE_CASH:cust-2:2026-10-30"),
                run.reports().stream().map(TransactionReport::id).toList());
    }

    @Test
    void theJsonFormHoldsWhatTheAuthorityNeeds() {
        TransactionReport report = reporting.run(DAY,
                List.of(cash("cust-1", TransactionType.CASH_DEPOSIT, 450_000_000, MORNING))).reports().getFirst();

        JsonNode json = ReportJson.toJson(report);

        assertEquals(ReportJson.TRANSACTION_SCHEMA, json.get("schema").asText());
        assertEquals("2026-11-02", json.get("dueBy").asText());
        JsonNode transaction = json.get("transactions").get(0);
        assertEquals("450000000", transaction.get("amount").get("value").asText());
        assertEquals(ACCOUNT.account(), transaction.get("account").get("account").asText());
        assertFalse(transaction.has("counterparty"));
    }

    @Test
    void deadlinesSkipTheBanksHolidays() {
        WorkingDays days = WorkingDays.mondayToFriday(Set.of(LocalDate.of(2026, 11, 2)));

        assertEquals(LocalDate.of(2026, 11, 3), days.plus(DAY, 1));
        assertEquals(LocalDate.of(2026, 11, 5), days.plus(DAY, 3));
        assertEquals(DAY, days.plus(DAY, 0));
    }

    @Test
    void suspiciousTransactionReportsComeFromCasesConcludedAsReported() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneOffset.UTC);
        CaseService cases = new CaseService(new InMemoryCaseStore(), clock);
        Investigator analyst = new Investigator("an.analyst", Set.of(Role.ANALYST));
        Investigator supervisor = new Investigator("binh.supervisor", Set.of(Role.SUPERVISOR));
        List<CaseFile> closed = new ArrayList<>();
        for (String customer : List.of("cust-1", "cust-2", "cust-3")) {
            CaseFile c = cases.raise(new Alert("evt-" + customer + ":PASS_THROUGH", "monitoring", "PASS_THROUGH",
                    customer, Optional.of("evt-" + customer), Optional.empty(), Map.of("severity", "HIGH"),
                    MORNING));
            cases.assign(analyst, c.id(), analyst);
            if (!customer.equals("cust-3")) {
                cases.propose(analyst, c.id(), customer.equals("cust-1") ? CaseFile.Outcome.REPORTED
                        : CaseFile.Outcome.FALSE_POSITIVE, "checked the counterparties");
                closed.add(cases.approve(supervisor, c.id()));
            } else {
                CaseFile open = cases.find(c.id()).orElseThrow();
                assertThrows(IllegalArgumentException.class, () -> SuspiciousTransactionReport.of(open));
            }
        }

        List<SuspiciousTransactionReport> reports = SuspiciousTransactionReport.fromCases(closed);

        assertEquals(1, reports.size());
        SuspiciousTransactionReport report = reports.getFirst();
        assertEquals("cust-1", report.subject());
        assertEquals(Optional.of("binh.supervisor"), report.conclusion().approvedBy());
        assertEquals(clock.instant(), report.concludedAt());
        JsonNode json = ReportJson.toJson(report);
        assertEquals(ReportJson.SUSPICIOUS_SCHEMA, json.get("schema").asText());
        assertEquals("evt-cust-1", json.get("alerts").get(0).get("eventId").asText());
        assertEquals("APPROVED", json.get("history").get(json.get("history").size() - 1).get("what").asText());
    }

    @Test
    void aPolicyWithoutDeadlinesIsNeverBuilt() throws IOException {
        String table = """
                {"id": "report_daily", "version": "1", "effectiveFrom": "2023-12-01", "hitPolicy": "COLLECT",
                 "inputs": {"cash": {"amount": "money"}}, "outputs": {"severity": "string"},
                 "rules": [{"id": "R1", "when": "true", "then": {"severity": "'HIGH'"}, "reasonCode": "R1"}]}
                """;
        var tables = List.of(DecisionTableJson.read(new ByteArrayInputStream(
                table.getBytes(StandardCharsets.UTF_8))));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ReportPolicy.compile(VN, tables));
        assertTrue(e.getMessage().contains("dueWorkingDays"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> ReportPolicy.compile(VN, List.of()));
    }
}
