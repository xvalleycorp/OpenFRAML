//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import vn.io.dxa.openframl.model.Decision;
import vn.io.dxa.openframl.pack.CountryPack;
import vn.io.dxa.openframl.pack.PackResource;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.cel.ValueType;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.RuleStone;
import vn.io.dxa.rulestone.core.Rule;

/**
 * The decision tables a gate runs, compiled and checked. Immutable: a gate swaps one policy for another as a whole,
 * and each decision runs on the policy that was in force when it started.
 *
 * <p>Compiling checks everything that can be checked before a payment arrives: RuleStone type-checks every rule,
 * and the gate requires {@value Gate#DECISION_ID} and {@value Gate#FALLBACK_ID} with a string output
 * {@code action} whose literal values are known decisions. A policy that fails any check is never built.
 *
 * <p>A policy holds every version of its tables, past ones included, so a decision of any day can be replayed with
 * the rules in force that day. Its {@link #id()} is a hash of the tables' content.
 */
public final class GatePolicy {

    private static final Pattern ACTION_LITERAL = Pattern.compile("'([A-Za-z_]+)'");

    private final List<DecisionTable> tables;
    private final RuleStoneEngine engine;
    private final String id;

    private GatePolicy(CountryPack pack, List<DecisionTable> tables) {
        this.tables = List.copyOf(tables);
        checkActions(this.tables, Gate.DECISION_ID);
        checkActions(this.tables, Gate.FALLBACK_ID);
        this.engine = RuleStone.builder().clock(Clock.system(pack.businessZone())).decisions(this.tables).build();
        this.id = hash(this.tables);
    }

    /**
     * Compiles tables against a country pack, whose business zone selects the version in force.
     *
     * @throws IllegalStateException if a gate table is missing or has unknown actions
     * @throws RuntimeException      RuleStone's own exception if a table does not type-check
     */
    public static GatePolicy compile(CountryPack pack, List<DecisionTable> tables) {
        return new GatePolicy(Objects.requireNonNull(pack, "pack"), tables);
    }

    /** The country pack's own (sample) tables. */
    public static GatePolicy of(CountryPack pack) {
        List<DecisionTable> tables = new ArrayList<>();
        for (PackResource resource : pack.decisionTables()) {
            try (InputStream in = resource.open()) {
                tables.add(DecisionTableJson.read(in));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + resource.name() + " from country pack "
                        + pack.countryCode(), e);
            }
        }
        return compile(pack, tables);
    }

    /**
     * Every {@code *.stone.json} in a directory: a bank's own tables, which it keeps confidential. The directory
     * holds every version of every table, past ones included.
     */
    public static GatePolicy fromDirectory(CountryPack pack, Path directory) {
        List<DecisionTable> tables;
        try (Stream<Path> files = Files.list(directory)) {
            tables = files.filter(file -> file.getFileName().toString().endsWith(".stone.json")).sorted()
                    .map(DecisionTableJson::read).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list decision tables in " + directory, e);
        }
        if (tables.isEmpty()) {
            throw new IllegalArgumentException("no *.stone.json in " + directory);
        }
        return compile(pack, tables);
    }

    /** {@code sha256:<hex>} over the tables' content, independent of their order. */
    public String id() {
        return id;
    }

    public List<DecisionTable> tables() {
        return tables;
    }

    RuleStoneEngine engine() {
        return engine;
    }

    @Override
    public String toString() {
        return "GatePolicy[" + id + ", " + tables.size() + " tables]";
    }

    private static String hash(List<DecisionTable> tables) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        tables.stream().map(DecisionTableJson::write).sorted(Comparator.naturalOrder())
                .forEach(json -> digest.update((json + '\n').getBytes(StandardCharsets.UTF_8)));
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private static void checkActions(List<DecisionTable> tables, String decisionId) {
        List<DecisionTable> versions = tables.stream().filter(table -> table.id().equals(decisionId)).toList();
        if (versions.isEmpty()) {
            throw new IllegalStateException("the policy has no decision table " + decisionId);
        }
        for (DecisionTable table : versions) {
            if (!ValueType.STRING.equals(table.outputs().get("action"))) {
                throw new IllegalStateException(table.ref() + " must declare the string output 'action'");
            }
            for (Rule rule : table.rules()) {
                String expression = rule.then().get("action");
                Matcher literal = expression == null ? null : ACTION_LITERAL.matcher(expression.strip());
                if (literal != null && literal.matches() && !isDecision(literal.group(1))) {
                    throw new IllegalStateException("%s rule %s sets unknown action %s; expected one of %s"
                            .formatted(table.ref(), rule.id(), literal.group(1), List.of(Decision.values())));
                }
            }
        }
    }

    private static boolean isDecision(String name) {
        for (Decision decision : Decision.values()) {
            if (decision.name().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
