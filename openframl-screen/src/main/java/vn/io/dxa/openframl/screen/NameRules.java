//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.screen;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import vn.io.dxa.openframl.pack.CountryPack;
import vn.io.dxa.openframl.pack.PackResource;

/**
 * How names are split into the tokens they are compared by: abbreviations written out ("TM" is "thuong mai" in
 * Vietnam), and the legal forms left out when the core of an organisation's name is compared ("Ltd", "TNHH").
 *
 * <p>{@link #BASE} knows the international legal forms only; a country pack adds its own in {@code names.properties}:
 *
 * <pre>
 * # An abbreviation, then what it stands for
 * abbreviation.TM = Thương mại
 * # Legal forms, separated by spaces
 * legalForms = Công ty TNHH Cổ phần
 * </pre>
 *
 * <p>Keys and values are normalised like names, so they may be written with their diacritics and in any case.
 */
public final class NameRules {

    /** Legal forms used across countries: "Co", "Ltd", "LLC", "Inc", "GmbH" and the like. */
    public static final NameRules BASE = new NameRules(Map.of(), Set.of("co", "jsc", "ltd", "llc", "inc", "corp",
            "corporation", "company", "limited", "plc", "gmbh", "group"));

    private final Map<String, List<String>> abbreviations;
    private final Set<String> legalForms;

    private NameRules(Map<String, List<String>> abbreviations, Set<String> legalForms) {
        this.abbreviations = Map.copyOf(abbreviations);
        this.legalForms = Set.copyOf(legalForms);
    }

    /** {@link #BASE} and the pack's own rules, if it has any. */
    public static NameRules of(CountryPack pack) {
        return pack.nameRules().map(NameRules::read).orElse(BASE);
    }

    /** {@link #BASE} and the rules of a {@code names.properties} file. */
    public static NameRules read(PackResource resource) {
        try (InputStream in = resource.open()) {
            return read(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + resource.name(), e);
        }
    }

    /**
     * @throws IllegalArgumentException if a key is neither {@code abbreviation.<abbreviation>} nor
     *                                  {@code legalForms}, or an abbreviation is not one token
     */
    public static NameRules read(InputStream in) throws IOException {
        Properties properties = new Properties();
        properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        Map<String, List<String>> abbreviations = new HashMap<>();
        Set<String> legalForms = new HashSet<>(BASE.legalForms);
        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key);
            if (key.equals("legalForms")) {
                legalForms.addAll(split(value));
            } else if (key.startsWith("abbreviation.")) {
                List<String> abbreviation = split(key.substring("abbreviation.".length()));
                List<String> meaning = split(value);
                if (abbreviation.size() != 1 || meaning.isEmpty()) {
                    throw new IllegalArgumentException("name rule " + key + ": an abbreviation is one word and "
                            + "stands for at least one");
                }
                abbreviations.put(abbreviation.getFirst(), meaning);
            } else {
                throw new IllegalArgumentException("unknown name rule " + key);
            }
        }
        return new NameRules(abbreviations, legalForms);
    }

    /** The tokens of {@code name}, normalised, with abbreviations written out. */
    public List<String> tokens(String name) {
        List<String> tokens = new ArrayList<>();
        for (String token : split(name)) {
            tokens.addAll(abbreviations.getOrDefault(token, List.of(token)));
        }
        return List.copyOf(tokens);
    }

    /** {@code tokens} without legal forms; for organisations only. */
    public List<String> core(List<String> tokens) {
        return tokens.stream().filter(token -> !legalForms.contains(token)).toList();
    }

    public Map<String, List<String>> abbreviations() {
        return abbreviations;
    }

    public Set<String> legalForms() {
        return legalForms;
    }

    private static List<String> split(String text) {
        String normalized = Names.normalize(Objects.requireNonNull(text));
        return normalized.isEmpty() ? List.of() : Arrays.asList(normalized.split(" "));
    }
}
