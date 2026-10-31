//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import vn.io.dxa.openframl.model.Amount;
import vn.io.dxa.openframl.model.TransactionEvent;
import vn.io.dxa.openframl.model.TransactionType;
import vn.io.dxa.openframl.pack.CountryPack;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.Money;

/**
 * Finds the transactions of one business day that must be reported, after the day closes.
 *
 * <ul>
 *   <li>Each transaction goes through {@value ReportPolicy#TRANSACTION_ID}; each rule that fires puts it in that
 *       report for its customer.</li>
 *   <li>Each customer's cash of the day is summed and goes through {@value ReportPolicy#DAILY_ID}; each rule that
 *       fires puts all of the customer's cash transactions of the day in that report, so cash split into smaller
 *       amounts is reported like one large amount.</li>
 *   <li>Amounts are compared in the national currency: foreign-currency amounts are converted with the bank's
 *       rates, and an amount without a rate is counted in {@link Run#unconverted()} but compared with nothing.
 *       Each transaction is also given in US dollars, the currency of FATF's thresholds for wire transfers.</li>
 *   <li>A transaction crosses the border when its counterparty's country is set and is not the pack's.</li>
 *   <li>Running a day again gives the same reports: transactions are told apart by event id, and a report's id is
 *       its kind, customer and day.</li>
 * </ul>
 *
 * <p>The events may come in any order; events of other business days are skipped and counted.
 */
public final class DailyReporting {

    private static final String USD = "USD";

    /**
     * What a run found.
     *
     * @param reports      the reports, by kind and then customer
     * @param transactions the day's transactions, each counted once
     * @param duplicates   events seen again
     * @param otherDays    events of another business day, skipped
     * @param unconverted  foreign-currency transactions without a rate, compared with no threshold
     */
    public record Run(LocalDate businessDate, List<TransactionReport> reports, long transactions, long duplicates,
            long otherDays, long unconverted) {

        public Run {
            reports = List.copyOf(reports);
        }
    }

    private final CountryPack pack;
    private final ReportPolicy policy;
    private final WorkingDays workingDays;
    private final ExchangeRates rates;

    public DailyReporting(CountryPack pack, ReportPolicy policy, WorkingDays workingDays, ExchangeRates rates) {
        this.pack = Objects.requireNonNull(pack, "pack");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.workingDays = Objects.requireNonNull(workingDays, "workingDays");
        this.rates = Objects.requireNonNull(rates, "rates");
    }

    public Run run(LocalDate businessDate, Iterable<TransactionEvent> events) {
        Set<String> seen = new HashSet<>();
        Map<String, Report> reports = new HashMap<>();
        Map<String, CashDay> cash = new LinkedHashMap<>();
        long transactions = 0;
        long duplicates = 0;
        long otherDays = 0;
        long unconverted = 0;
        for (TransactionEvent event : events) {
            if (!LocalDate.ofInstant(event.occurredAt(), pack.businessZone()).equals(businessDate)) {
                otherDays++;
                continue;
            }
            if (!seen.add(event.eventId())) {
                duplicates++;
                continue;
            }
            transactions++;
            Optional<BigDecimal> national = national(event.amount(), businessDate);
            if (national.isEmpty()) {
                unconverted++;
            }
            if (policy.has(ReportPolicy.TRANSACTION_ID)) {
                DecisionResult result = evaluate(ReportPolicy.TRANSACTION_ID, businessDate,
                        transactionFacts(event, national, businessDate), event.eventId());
                collect(reports, result, event.customerId(), businessDate, List.of(event));
            }
            if (isCash(event.type())) {
                cash.computeIfAbsent(event.customerId(), customer -> new CashDay()).add(event, national);
            }
        }
        if (policy.has(ReportPolicy.DAILY_ID)) {
            for (Map.Entry<String, CashDay> day : cash.entrySet()) {
                DecisionResult result = evaluate(ReportPolicy.DAILY_ID, businessDate, day.getValue().facts(),
                        "daily:" + day.getKey() + ":" + businessDate);
                collect(reports, result, day.getKey(), businessDate, day.getValue().transactions);
            }
        }
        List<TransactionReport> built = reports.values().stream()
                .sorted(Comparator.comparing((Report r) -> r.kind).thenComparing(r -> r.subject))
                .map(r -> r.build(businessDate, workingDays)).toList();
        return new Run(businessDate, built, transactions, duplicates, otherDays, unconverted);
    }

    private DecisionResult evaluate(String table, LocalDate businessDate, Facts facts, String correlationId) {
        return policy.engine().evaluate(DecisionRef.of(table).asOf(businessDate).correlationId(correlationId), facts);
    }

    private static void collect(Map<String, Report> reports, DecisionResult result, String subject,
            LocalDate businessDate, List<TransactionEvent> transactions) {
        List<String> kinds = result.reasonCodes();
        List<?> due = list(result.output().get("dueWorkingDays"));
        for (int i = 0; i < kinds.size(); i++) {
            String kind = kinds.get(i);
            int days = ((Number) due.get(i)).intValue();
            Report report = reports.computeIfAbsent(kind + ':' + subject + ':' + businessDate,
                    id -> new Report(id, kind, subject));
            report.tables.add(result.decisionId() + "@" + result.ruleVersion());
            report.dueWorkingDays = Math.min(report.dueWorkingDays, days);
            transactions.forEach(t -> report.transactions.putIfAbsent(t.eventId(), t));
        }
    }

    private static List<?> list(Object output) {
        if (output instanceof List<?> values) {
            return values;
        }
        return output == null ? List.of() : List.of(output);
    }

    private Facts transactionFacts(TransactionEvent event, Optional<BigDecimal> national, LocalDate businessDate) {
        Map<String, Object> transaction = new LinkedHashMap<>();
        transaction.put("amount", Money.of(event.amount().value(), event.amount().currency()));
        national.ifPresent(value -> transaction.put("nationalAmount", Money.of(value, pack.currency())));
        usd(event.amount(), national, businessDate).ifPresent(value -> transaction.put("usdAmount",
                Money.of(value, USD)));
        String country = event.counterpartyCountry();
        if (country != null) {
            transaction.put("counterpartyCountry", country);
        }
        transaction.put("crossBorder", country != null && !country.equals(pack.countryCode()));
        transaction.put("type", event.type().name());
        transaction.put("channel", event.channel().name());
        transaction.put("direction", isIncoming(event.type()) ? "IN" : "OUT");
        transaction.put("cash", isCash(event.type()));
        return Facts.from(Map.of("transaction", transaction));
    }

    private Optional<BigDecimal> national(Amount amount, LocalDate businessDate) {
        String currency = amount.currency();
        if (currency.equals(pack.currency())) {
            return Optional.of(amount.value());
        }
        return rates.rate(currency, businessDate).map(rate -> amount.value().multiply(rate));
    }

    /** The amount in US dollars, through the national currency, to the cent. */
    private Optional<BigDecimal> usd(Amount amount, Optional<BigDecimal> national, LocalDate businessDate) {
        if (amount.currency().equals(USD)) {
            return Optional.of(amount.value());
        }
        return national.flatMap(value -> national(new Amount(BigDecimal.ONE, USD), businessDate)
                .filter(rate -> rate.signum() > 0)
                .map(rate -> value.divide(rate, 2, RoundingMode.HALF_EVEN)));
    }

    static boolean isCash(TransactionType type) {
        return type == TransactionType.CASH_DEPOSIT || type == TransactionType.CASH_WITHDRAWAL;
    }

    static boolean isIncoming(TransactionType type) {
        return type == TransactionType.TRANSFER_IN || type == TransactionType.CASH_DEPOSIT;
    }

    /** One customer's cash of the day. */
    private final class CashDay {
        final List<TransactionEvent> transactions = new ArrayList<>();
        long inCount;
        long outCount;
        BigDecimal in = BigDecimal.ZERO;
        BigDecimal out = BigDecimal.ZERO;

        void add(TransactionEvent event, Optional<BigDecimal> national) {
            transactions.add(event);
            boolean incoming = isIncoming(event.type());
            if (incoming) {
                inCount++;
            } else {
                outCount++;
            }
            national.ifPresent(value -> {
                if (incoming) {
                    in = in.add(value);
                } else {
                    out = out.add(value);
                }
            });
        }

        Facts facts() {
            String currency = pack.currency();
            return Facts.from(Map.of(
                    "cashIn", Map.of("count", inCount, "amount", Money.of(in, currency)),
                    "cashOut", Map.of("count", outCount, "amount", Money.of(out, currency)),
                    "cash", Map.of("count", inCount + outCount, "amount", Money.of(in.add(out), currency))));
        }
    }

    private static final class Report {
        final String id;
        final String kind;
        final String subject;
        final Set<String> tables = new TreeSet<>();
        final Map<String, TransactionEvent> transactions = new LinkedHashMap<>();
        int dueWorkingDays = Integer.MAX_VALUE;

        Report(String id, String kind, String subject) {
            this.id = id;
            this.kind = kind;
            this.subject = subject;
        }

        TransactionReport build(LocalDate businessDate, WorkingDays workingDays) {
            List<TransactionEvent> sorted = transactions.values().stream()
                    .sorted(Comparator.comparing(TransactionEvent::occurredAt).thenComparing(TransactionEvent::eventId))
                    .toList();
            return new TransactionReport(id, kind, subject, businessDate,
                    workingDays.plus(businessDate, dueWorkingDays), List.copyOf(tables), sorted);
        }
    }
}
