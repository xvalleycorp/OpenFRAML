//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.monitor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import vn.io.dxa.openframl.cases.Alert;
import vn.io.dxa.openframl.model.AccountRef;
import vn.io.dxa.openframl.model.TransactionEvent;
import vn.io.dxa.openframl.model.TransactionType;
import vn.io.dxa.openframl.pack.CountryPack;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;

/**
 * Runs the AML scenarios after each transaction: records the movement on the bank's account, sums up the account's
 * recent activity, evaluates {@value MonitorPolicy#SCENARIOS_ID} as of the transaction's business date, and raises an
 * alert for every scenario that fires.
 *
 * <ul>
 *   <li>The alert is about the bank's customer; its id is the event and the scenario, so a transaction delivered
 *       twice is counted once and raises nothing new.</li>
 *   <li>A scenario that keeps firing for a customer raises one alert per suppression period (7 days by default),
 *       not one per transaction: the open case already holds it.</li>
 *   <li>The evidence is the scenario, its severity and message, the table version, and the activity it saw;
 *       never an account number.</li>
 * </ul>
 *
 * <p>The movement is recorded only once its alerts have gone, so a transaction whose alerts could not be raised
 * (Case Management unreachable) is evaluated again, with the same result, when it is processed again.
 *
 * <p>Transactions of one account must be processed in order, one at a time; the suppression state is kept in this
 * process.
 */
public final class TransactionMonitor {

    private final CountryPack pack;
    private final MonitorPolicy policy;
    private final ActivityStore store;
    private final Consumer<Alert> alerts;
    private final Duration suppression;
    private final Map<String, Instant> lastAlert = new ConcurrentHashMap<>();

    /**
     * @param alerts      where alerts go, e.g. {@code caseService::raise}
     * @param suppression how long a scenario that fired for a customer stays quiet
     */
    public TransactionMonitor(CountryPack pack, MonitorPolicy policy, ActivityStore store, Consumer<Alert> alerts,
            Duration suppression) {
        this.pack = Objects.requireNonNull(pack, "pack");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.store = Objects.requireNonNull(store, "store");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.suppression = Objects.requireNonNull(suppression, "suppression");
    }

    /**
     * Monitors one transaction.
     *
     * @return the alerts raised
     */
    public List<Alert> process(TransactionEvent event) {
        String account = event.originator().key();
        Movement movement = movement(event);
        if (store.contains(account, movement.eventId())) {
            return List.of();
        }
        Optional<Instant> lastActivity = store.lastActivity(account);
        List<Movement> recent = new ArrayList<>(store.since(account, movement.at().minus(Duration.ofDays(30))));
        recent.add(movement);
        MonitoringFacts.Computed computed = MonitoringFacts.of(event, movement, recent, lastActivity);
        LocalDate businessDate = LocalDate.ofInstant(event.occurredAt(), pack.businessZone());
        DecisionResult result = policy.engine().evaluate(DecisionRef.of(MonitorPolicy.SCENARIOS_ID)
                .asOf(businessDate).correlationId(event.eventId()), computed.facts());

        List<String> scenarios = result.reasonCodes();
        List<?> severities = severities(result.output().get("severity"));
        List<Alert> raised = new ArrayList<>();
        for (int i = 0; i < scenarios.size(); i++) {
            String scenario = scenarios.get(i);
            String key = event.customerId() + '\u001f' + scenario;
            Instant previous = lastAlert.get(key);
            if (previous != null && movement.at().isBefore(previous.plus(suppression))) {
                continue;
            }
            Map<String, String> evidence = new LinkedHashMap<>();
            evidence.put("scenario", scenario);
            evidence.put("severity", i < severities.size() ? String.valueOf(severities.get(i)) : "");
            evidence.put("message", pack.reasonMessage(scenario, Locale.ENGLISH).orElse(scenario));
            evidence.put("table", MonitorPolicy.SCENARIOS_ID + "@" + result.ruleVersion());
            evidence.put("amount", event.amount().toString());
            evidence.put("direction", movement.direction().name());
            evidence.putAll(computed.summary());
            Alert alert = new Alert(event.eventId() + ":" + scenario, "monitoring", scenario, event.customerId(),
                    Optional.of(event.eventId()), Optional.empty(), evidence, event.occurredAt());
            alerts.accept(alert);
            lastAlert.put(key, movement.at());
            raised.add(alert);
        }
        store.add(account, movement);
        return raised;
    }

    private static List<?> severities(Object output) {
        if (output instanceof List<?> list) {
            return list;
        }
        return output == null ? List.of() : List.of(output);
    }

    /** The movement on the bank's account; the account the model calls the originator, both ways. */
    static Movement movement(TransactionEvent event) {
        TransactionType type = event.type();
        Movement.Direction direction = type == TransactionType.TRANSFER_IN || type == TransactionType.CASH_DEPOSIT
                ? Movement.Direction.IN : Movement.Direction.OUT;
        boolean cash = type == TransactionType.CASH_DEPOSIT || type == TransactionType.CASH_WITHDRAWAL;
        AccountRef counterparty = event.beneficiary();
        return new Movement(event.eventId(), event.occurredAt(), direction, event.amount(), cash,
                counterparty == null ? null : hash(counterparty.key()));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
