//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.monitor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import vn.io.dxa.openframl.model.Amount;
import vn.io.dxa.openframl.model.TransactionEvent;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.Money;

/**
 * What the scenario tables see about one transaction and its account's recent activity, the transaction included.
 * Only amounts in the transaction's currency are summed. The contract is documented in {@code docs/monitoring.md}.
 */
final class MonitoringFacts {

    /**
     * @param facts   for the scenario tables
     * @param summary the same activity as text, for the alert's evidence
     */
    record Computed(Facts facts, Map<String, String> summary) {}

    private MonitoringFacts() {}

    static Computed of(TransactionEvent event, Movement movement, List<Movement> recent,
            Optional<Instant> lastActivityBefore) {
        Instant at = movement.at();
        String currency = movement.amount().currency();
        Map<String, Object> facts = new LinkedHashMap<>();

        Map<String, Object> transaction = new LinkedHashMap<>();
        transaction.put("amount", money(movement.amount()));
        transaction.put("type", event.type().name());
        transaction.put("channel", event.channel().name());
        transaction.put("direction", movement.direction().name());
        transaction.put("cash", movement.cash());
        facts.put("transaction", transaction);

        Map<String, Object> account = new LinkedHashMap<>();
        lastActivityBefore.ifPresent(last -> account.put("daysSinceLastActivity",
                Duration.between(last, at).toDays()));
        facts.put("account", account);

        Window cashIn24h = window(recent, at, Duration.ofHours(24), currency, m -> m.cash()
                && m.direction() == Movement.Direction.IN);
        facts.put("cashIn24h", Map.of("count", cashIn24h.count, "amount", money(cashIn24h.amount, currency),
                "maxAmount", money(cashIn24h.max, currency)));

        Window in24h = window(recent, at, Duration.ofHours(24), currency,
                m -> m.direction() == Movement.Direction.IN);
        facts.put("in24h", Map.of("count", in24h.count, "amount", money(in24h.amount, currency),
                "distinctCounterparties", (long) in24h.counterparties.size()));

        Window in48h = window(recent, at, Duration.ofHours(48), currency,
                m -> m.direction() == Movement.Direction.IN);
        Window out48h = window(recent, at, Duration.ofHours(48), currency,
                m -> m.direction() == Movement.Direction.OUT);
        facts.put("in48h", Map.of("amount", money(in48h.amount, currency)));
        facts.put("out48h", Map.of("amount", money(out48h.amount, currency)));
        Map<String, Object> flow = new LinkedHashMap<>();
        if (in48h.amount.signum() > 0) {
            flow.put("outToIn", out48h.amount.divide(in48h.amount, 4, RoundingMode.HALF_EVEN));
        }
        facts.put("flow48h", flow);

        Map<String, String> summary = new LinkedHashMap<>();
        account.forEach((name, value) -> summary.put("account." + name, value.toString()));
        summary.put("cashIn24h.count", Long.toString(cashIn24h.count));
        summary.put("cashIn24h.amount", cashIn24h.amount.toPlainString() + " " + currency);
        summary.put("cashIn24h.maxAmount", cashIn24h.max.toPlainString() + " " + currency);
        summary.put("in24h.count", Long.toString(in24h.count));
        summary.put("in24h.distinctCounterparties", Integer.toString(in24h.counterparties.size()));
        summary.put("in48h.amount", in48h.amount.toPlainString() + " " + currency);
        summary.put("out48h.amount", out48h.amount.toPlainString() + " " + currency);
        flow.forEach((name, value) -> summary.put("flow48h." + name, ((BigDecimal) value).toPlainString()));
        return new Computed(Facts.from(facts), summary);
    }

    private static final class Window {
        long count;
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal max = BigDecimal.ZERO;
        final Set<String> counterparties = new HashSet<>();
    }

    private static Window window(List<Movement> recent, Instant at, Duration length, String currency,
            java.util.function.Predicate<Movement> which) {
        Window window = new Window();
        Instant from = at.minus(length);
        for (Movement m : recent) {
            if (m.at().isAfter(from) && !m.at().isAfter(at) && which.test(m)
                    && m.amount().currency().equals(currency)) {
                window.count++;
                window.amount = window.amount.add(m.amount().value());
                window.max = window.max.max(m.amount().value());
                if (m.counterparty() != null) {
                    window.counterparties.add(m.counterparty());
                }
            }
        }
        return window;
    }

    private static Money money(Amount amount) {
        return Money.of(amount.value(), amount.currency());
    }

    private static Money money(BigDecimal value, String currency) {
        return Money.of(value, currency);
    }
}
