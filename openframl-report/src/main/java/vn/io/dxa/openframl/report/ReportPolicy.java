//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 * The threshold tables Regulatory Reporting runs, compiled and checked: RuleStone type-checks every rule, and every
 * version of {@value #TRANSACTION_ID} and {@value #DAILY_ID} must declare the int output {@code dueWorkingDays},
 * which every rule sets to a whole number of working days from 1 to 99, and give every rule a reason code (the
 * reason code names the report). A policy that fails a check is never built.
 */
public final class ReportPolicy {

    /** Evaluated per transaction, hit policy {@code COLLECT}: each rule that fires puts the transaction in a report. */
    public static final String TRANSACTION_ID = "report_transaction";

    /**
     * Evaluated per customer and business day on the day's cash, hit policy {@code COLLECT}: each rule that fires
     * puts all of the customer's cash transactions of the day in a report.
     */
    public static final String DAILY_ID = "report_daily";

    private static final Pattern WORKING_DAYS = Pattern.compile("[1-9][0-9]?");

    private final List<DecisionTable> tables;
    private final RuleStoneEngine engine;

    private ReportPolicy(CountryPack pack, List<DecisionTable> tables) {
        this.tables = List.copyOf(tables);
        check(this.tables);
        this.engine = RuleStone.builder().clock(Clock.system(pack.businessZone())).decisions(this.tables).build();
    }

    public static ReportPolicy compile(CountryPack pack, List<DecisionTable> tables) {
        return new ReportPolicy(Objects.requireNonNull(pack, "pack"), tables);
    }

    /** The country pack's own thresholds. */
    public static ReportPolicy of(CountryPack pack) {
        List<DecisionTable> tables = new ArrayList<>();
        for (PackResource resource : pack.reportingTables()) {
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

    /** Whether the policy has a table with this id. */
    boolean has(String id) {
        return tables.stream().anyMatch(table -> table.id().equals(id));
    }

    RuleStoneEngine engine() {
        return engine;
    }

    private static void check(List<DecisionTable> tables) {
        List<DecisionTable> versions = tables.stream()
                .filter(table -> table.id().equals(TRANSACTION_ID) || table.id().equals(DAILY_ID)).toList();
        if (versions.isEmpty()) {
            throw new IllegalStateException("the policy has neither " + TRANSACTION_ID + " nor " + DAILY_ID);
        }
        for (DecisionTable table : versions) {
            if (!ValueType.INT.equals(table.outputs().get("dueWorkingDays"))) {
                throw new IllegalStateException(table.ref() + " must declare the int output 'dueWorkingDays'");
            }
            for (Rule rule : table.rules()) {
                if (rule.reasonCode().isEmpty()) {
                    throw new IllegalStateException(table.ref() + " rule " + rule.id()
                            + " has no reason code: the reason code names the report");
                }
                String expression = rule.then().get("dueWorkingDays");
                if (expression == null || !WORKING_DAYS.matcher(expression.strip()).matches()) {
                    throw new IllegalStateException(table.ref() + " rule " + rule.id()
                            + " must set dueWorkingDays to a number of working days from 1 to 99");
                }
            }
        }
    }
}
