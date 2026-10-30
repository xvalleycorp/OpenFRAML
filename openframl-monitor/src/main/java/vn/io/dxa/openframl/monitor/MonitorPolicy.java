//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.monitor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vn.io.dxa.openframl.pack.CountryPack;
import vn.io.dxa.openframl.pack.PackResource;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.cel.ValueType;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.Rule;
import vn.io.dxa.rulestone.core.RuleStone;

/**
 * The scenario tables Transaction Monitoring runs, compiled and checked: RuleStone type-checks every rule, and
 * {@value #SCENARIOS_ID} must declare the string output {@code severity}, set to a known severity by every rule that
 * has a reason code (the reason code names the scenario). A policy that fails a check is never built.
 */
public final class MonitorPolicy {

    /** The scenario table, with hit policy {@code COLLECT}: every scenario that fires raises an alert. */
    public static final String SCENARIOS_ID = "monitor_scenarios";

    /** Severities a scenario may set. */
    public static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH");

    private static final Pattern LITERAL = Pattern.compile("'([A-Za-z_]+)'");

    private final List<DecisionTable> tables;
    private final RuleStoneEngine engine;

    private MonitorPolicy(CountryPack pack, List<DecisionTable> tables) {
        this.tables = List.copyOf(tables);
        check(this.tables);
        this.engine = RuleStone.builder().clock(Clock.system(pack.businessZone())).decisions(this.tables).build();
    }

    public static MonitorPolicy compile(CountryPack pack, List<DecisionTable> tables) {
        return new MonitorPolicy(Objects.requireNonNull(pack, "pack"), tables);
    }

    /** The country pack's own (sample) scenarios. */
    public static MonitorPolicy of(CountryPack pack) {
        List<DecisionTable> tables = new ArrayList<>();
        for (PackResource resource : pack.monitoringTables()) {
            try (InputStream in = resource.open()) {
                tables.add(DecisionTableJson.read(in));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + resource.name() + " from country pack "
                        + pack.countryCode(), e);
            }
        }
        return compile(pack, tables);
    }

    public List<DecisionTable> tables() {
        return tables;
    }

    RuleStoneEngine engine() {
        return engine;
    }

    private static void check(List<DecisionTable> tables) {
        List<DecisionTable> versions = tables.stream().filter(table -> table.id().equals(SCENARIOS_ID)).toList();
        if (versions.isEmpty()) {
            throw new IllegalStateException("the policy has no decision table " + SCENARIOS_ID);
        }
        for (DecisionTable table : versions) {
            if (!ValueType.STRING.equals(table.outputs().get("severity"))) {
                throw new IllegalStateException(table.ref() + " must declare the string output 'severity'");
            }
            for (Rule rule : table.rules()) {
                if (rule.reasonCode().isEmpty()) {
                    throw new IllegalStateException(table.ref() + " rule " + rule.id()
                            + " has no reason code: the reason code names the scenario");
                }
                String expression = rule.then().get("severity");
                Matcher literal = expression == null ? null : LITERAL.matcher(expression.strip());
                if (literal == null || !literal.matches() || !SEVERITIES.contains(literal.group(1))) {
                    throw new IllegalStateException(table.ref() + " rule " + rule.id() + " must set severity to one of "
                            + SEVERITIES);
                }
            }
        }
    }
}
