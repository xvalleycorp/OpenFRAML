//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Screens names against a set of lists. Built once per publication of the lists and immutable: a new publication
 * builds a new screener, so a screening sees one version of every list.
 *
 * <p>A name is looked up through an index of the three-letter pieces of every listed name and alias, and only the
 * names sharing enough of them are scored, so a screening does not compare against the whole list. Each entry
 * appears once, with the closest of its names.
 *
 * <p>Besides the name, what the subject and the entry both give is compared: the same identifier makes it a match
 * whatever the name; a different kind (a person against an organisation) or birth years more than a year apart lower
 * the score. Every adjustment is listed in the match's reasons.
 */
public final class Screener {

    /** A query must share this share of its three-letter pieces with a name for the name to be scored. */
    private static final double CANDIDATE_SHARE = 0.5;

    private static final int[] NONE = {};

    private record Indexed(int entry, String name, List<String> tokens) {}

    private final List<ListEntry> entries = new ArrayList<>();
    private final List<ScreeningList> listOf = new ArrayList<>();
    private final List<Indexed> names = new ArrayList<>();
    private final Map<String, int[]> trigrams = new HashMap<>();
    private final Map<String, List<Integer>> identifiers = new HashMap<>();

    private Screener(List<ScreeningList> lists) {
        Map<String, List<Integer>> postings = new HashMap<>();
        for (ScreeningList list : lists) {
            for (ListEntry entry : list.entries()) {
                int e = entries.size();
                entries.add(entry);
                listOf.add(list);
                for (String name : entry.names()) {
                    List<String> tokens = Names.tokens(name);
                    if (tokens.isEmpty()) {
                        continue;
                    }
                    int n = names.size();
                    names.add(new Indexed(e, name, tokens));
                    for (String trigram : trigrams(tokens)) {
                        postings.computeIfAbsent(trigram, key -> new ArrayList<>()).add(n);
                    }
                }
                for (String identifier : entry.identifiers()) {
                    if (!identifier(identifier).isEmpty()) {
                        identifiers.computeIfAbsent(identifier(identifier), key -> new ArrayList<>()).add(e);
                    }
                }
            }
        }
        postings.forEach((trigram, ids) -> trigrams.put(trigram, ids.stream().mapToInt(Integer::intValue).toArray()));
    }

    public static Screener of(List<ScreeningList> lists) {
        return new Screener(lists);
    }

    /** Listed entries and aliases, for sizing and monitoring. */
    public int names() {
        return names.size();
    }

    /**
     * The entries that may be {@code subject}, closest first.
     *
     * @param threshold the lowest score reported, e.g. 0.85
     */
    public List<ScreeningMatch> screen(Subject subject, double threshold) {
        Map<Integer, ScreeningMatch> best = new LinkedHashMap<>();
        for (String identifier : subject.identifiers()) {
            if (identifier(identifier).isEmpty()) {
                continue;
            }
            for (int e : identifiers.getOrDefault(identifier(identifier), List.of())) {
                best.put(e, match(e, entries.get(e).names().getFirst(), 1.0,
                        List.of("IDENTIFIER_MATCHES " + identifier)));
            }
        }

        List<String> query = Names.tokens(subject.name());
        Set<String> pieces = trigrams(query);
        int[] shared = new int[names.size()];
        List<Integer> touched = new ArrayList<>();
        for (String piece : pieces) {
            for (int n : trigrams.getOrDefault(piece, NONE)) {
                if (shared[n]++ == 0) {
                    touched.add(n);
                }
            }
        }
        double needed = Math.max(1, CANDIDATE_SHARE * pieces.size());
        for (int n : touched) {
            if (shared[n] < needed) {
                continue;
            }
            Indexed name = names.get(n);
            if (best.containsKey(name.entry()) && best.get(name.entry()).score() >= 1.0) {
                continue;
            }
            double nameScore = NameSimilarity.score(query, name.tokens());
            if (entries.get(name.entry()).kind() == ListEntry.Kind.ORGANISATION) {
                // "Hoang Long Co" is "Cong ty TNHH Hoang Long": compare the core of the name too
                nameScore = Math.max(nameScore, NameSimilarity.score(Names.core(query), Names.core(name.tokens())));
            }
            List<String> reasons = new ArrayList<>();
            reasons.add(String.format(Locale.ROOT, "NAME %.2f", nameScore));
            double score = adjust(nameScore, subject, entries.get(name.entry()), reasons);
            ScreeningMatch previous = best.get(name.entry());
            if (score >= threshold && (previous == null || score > previous.score())) {
                best.put(name.entry(), match(name.entry(), name.name(), score, reasons));
            }
        }
        return best.values().stream().filter(match -> match.score() >= threshold)
                .sorted(Comparator.comparingDouble(ScreeningMatch::score).reversed()).toList();
    }

    private static double adjust(double score, Subject subject, ListEntry entry, List<String> reasons) {
        if (subject.kind().isPresent() && comparable(subject.kind().get()) && comparable(entry.kind())
                && subject.kind().get() != entry.kind()) {
            score *= 0.85;
            reasons.add("KIND_DIFFERS");
        }
        if (subject.birthYear().isPresent() && entry.birthYear().isPresent()) {
            int apart = Math.abs(subject.birthYear().get() - entry.birthYear().get());
            if (apart > 1) {
                score *= 0.8;
                reasons.add("BIRTH_YEAR_DIFFERS");
            } else {
                reasons.add("BIRTH_YEAR_MATCHES");
            }
        }
        return score;
    }

    private static boolean comparable(ListEntry.Kind kind) {
        return kind == ListEntry.Kind.PERSON || kind == ListEntry.Kind.ORGANISATION;
    }

    private ScreeningMatch match(int e, String matchedName, double score, List<String> reasons) {
        ScreeningList list = listOf.get(e);
        return new ScreeningMatch(list.source(), list.version(), entries.get(e), matchedName, score, reasons);
    }

    private static Set<String> trigrams(List<String> tokens) {
        Set<String> pieces = new HashSet<>();
        for (String token : tokens) {
            String padded = " " + token + " ";
            for (int i = 0; i + 3 <= padded.length(); i++) {
                pieces.add(padded.substring(i, i + 3));
            }
        }
        return pieces;
    }

    private static String identifier(String value) {
        return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }
}
