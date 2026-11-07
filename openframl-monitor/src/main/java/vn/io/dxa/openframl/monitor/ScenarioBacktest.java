//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.monitor;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import vn.io.dxa.openframl.cases.Alert;
import vn.io.dxa.openframl.model.TransactionEvent;
import vn.io.dxa.openframl.monitor.memory.InMemoryActivityStore;
import vn.io.dxa.openframl.pack.CountryPack;
import vn.io.dxa.rulestone.core.DecisionTable;

/**
 * Runs past transactions through the scenario tables in force and through a candidate, and says what would change,
 * so that whoever approves a change to a scenario sees how many alerts it adds or removes first. Nothing is raised:
 * alerts are only counted, in memory, and no case is touched.
 *
 * <ul>
 *   <li>Both runs see the same transactions, with their own activity and suppression, as the live monitor would.</li>
 *   <li>Transactions before {@code countFrom} only build up the accounts' history, so that the windows of the first
 *       days counted are full. Give them 30 days ahead.</li>
 *   <li>A candidate is usually effective from a future date. It is evaluated as of its own latest effective date for
 *       transactions older than that, so it applies to the whole period.</li>
 * </ul>
 *
 * <p>Transactions of one account must come in order. The result holds event ids and counts, never an account number.
 */
public final class ScenarioBacktest {

    /** How many event ids of added and removed alerts each scenario keeps, for someone to look at. */
    public static final int SAMPLE_SIZE = 20;

    /**
     * What would change for one scenario.
     *
     * @param scenario       the scenario
     * @param current        alerts under the tables in force
     * @param candidate      alerts under the candidate
     * @param added          alerts only the candidate raises
     * @param removed        alerts only the tables in force raise
     * @param addedSample    event ids of some added alerts
     * @param removedSample  event ids of some removed alerts
     */
    public record ScenarioChange(String scenario, long current, long candidate, long added, long removed,
            List<String> addedSample, List<String> removedSample) {

        public ScenarioChange {
            addedSample = List.copyOf(addedSample);
            removedSample = List.copyOf(removedSample);
        }
    }

    /**
     * @param transactions the transactions counted, each once
     * @param history      the transactions before {@code countFrom}, each once
     * @param scenarios    each scenario either run raised an alert for, by name
     */
    public record Result(long transactions, long history, List<ScenarioChange> scenarios) {

        public Result {
            scenarios = List.copyOf(scenarios);
        }
    }

    private ScenarioBacktest() {}

    /**
     * @param current     the tables in force
     * @param candidate   the proposed tables
     * @param events      the transactions, those of one account in order
     * @param countFrom   alerts on transactions from this time on are counted
     * @param suppression as for the live monitor
     */
    public static Result run(CountryPack pack, MonitorPolicy current, MonitorPolicy candidate,
            Iterable<TransactionEvent> events, Instant countFrom, Duration suppression) {
        Objects.requireNonNull(countFrom, "countFrom");
        Map<String, Set<String>> currentAlerts = new HashMap<>();
        Map<String, Set<String>> candidateAlerts = new HashMap<>();
        InMemoryActivityStore currentStore = new InMemoryActivityStore();
        TransactionMonitor currentRun = new TransactionMonitor(pack, current, currentStore,
                collector(currentAlerts, countFrom), suppression, null);
        TransactionMonitor candidateRun = new TransactionMonitor(pack, candidate, new InMemoryActivityStore(),
                collector(candidateAlerts, countFrom), suppression, latestEffectiveDate(candidate));
        long transactions = 0;
        long history = 0;
        for (TransactionEvent event : events) {
            if (currentStore.contains(event.originator().key(), event.eventId())) {
                continue;
            }
            if (event.occurredAt().isBefore(countFrom)) {
                history++;
            } else {
                transactions++;
            }
            currentRun.process(event);
            candidateRun.process(event);
        }
        Set<String> scenarios = new TreeSet<>(currentAlerts.keySet());
        scenarios.addAll(candidateAlerts.keySet());
        List<ScenarioChange> changes = new ArrayList<>();
        for (String scenario : scenarios) {
            Set<String> before = currentAlerts.getOrDefault(scenario, Set.of());
            Set<String> after = candidateAlerts.getOrDefault(scenario, Set.of());
            List<String> added = new TreeSet<>(difference(after, before)).stream().toList();
            List<String> removed = new TreeSet<>(difference(before, after)).stream().toList();
            changes.add(new ScenarioChange(scenario, before.size(), after.size(), added.size(), removed.size(),
                    sample(added), sample(removed)));
        }
        return new Result(transactions, history, changes);
    }

    private static java.util.function.Consumer<Alert> collector(Map<String, Set<String>> alerts, Instant countFrom) {
        return alert -> {
            if (!alert.raisedAt().isBefore(countFrom)) {
                alerts.computeIfAbsent(alert.type(), scenario -> new HashSet<>()).add(alert.eventId().orElseThrow());
            }
        };
    }

    private static LocalDate latestEffectiveDate(MonitorPolicy policy) {
        return policy.tables().stream().filter(table -> table.id().equals(MonitorPolicy.SCENARIOS_ID))
                .map(DecisionTable::effectiveFrom).flatMap(java.util.Optional::stream)
                .max(LocalDate::compareTo).orElse(null);
    }

    private static Set<String> difference(Set<String> from, Set<String> without) {
        Set<String> left = new HashSet<>(from);
        left.removeAll(without);
        return left;
    }

    private static List<String> sample(List<String> ids) {
        return ids.subList(0, Math.min(SAMPLE_SIZE, ids.size()));
    }
}
