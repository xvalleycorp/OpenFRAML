//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * How alike two names are, from 0 to 1, whatever the order of their parts: lists write "Nguyen Van An", payments
 * "An Nguyen Van" or "NGUYEN VAN AN".
 *
 * <p>Tokens are paired best first, each used once, by Jaro-Winkler similarity; a pair below {@value #TOKEN_FLOOR}
 * counts as no match. The score is the length-weighted similarity of the pairs over the length of both names, so a
 * missing or extra part lowers it: "Nguyen An" against "Nguyen Van An" scores about 0.84.
 */
public final class NameSimilarity {

    /** Below this, two tokens are different words, not spellings of one. */
    public static final double TOKEN_FLOOR = 0.8;

    private NameSimilarity() {}

    public static double score(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        record Pair(int i, int j, double similarity) {}
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            for (int j = 0; j < b.size(); j++) {
                double similarity = jaroWinkler(a.get(i), b.get(j));
                if (similarity >= TOKEN_FLOOR) {
                    pairs.add(new Pair(i, j, similarity));
                }
            }
        }
        pairs.sort(Comparator.comparingDouble(Pair::similarity).reversed());
        boolean[] usedA = new boolean[a.size()];
        boolean[] usedB = new boolean[b.size()];
        double matched = 0;
        for (Pair pair : pairs) {
            if (!usedA[pair.i()] && !usedB[pair.j()]) {
                usedA[pair.i()] = true;
                usedB[pair.j()] = true;
                matched += pair.similarity() * (a.get(pair.i()).length() + b.get(pair.j()).length());
            }
        }
        return matched / (length(a) + length(b));
    }

    private static int length(List<String> tokens) {
        return tokens.stream().mapToInt(String::length).sum();
    }

    /** Jaro-Winkler similarity, with the usual prefix scale of 0.1 over at most four characters. */
    static double jaroWinkler(String s, String t) {
        if (s.equals(t)) {
            return 1;
        }
        int window = Math.max(0, Math.max(s.length(), t.length()) / 2 - 1);
        boolean[] sMatched = new boolean[s.length()];
        boolean[] tMatched = new boolean[t.length()];
        int matches = 0;
        for (int i = 0; i < s.length(); i++) {
            for (int j = Math.max(0, i - window); j < Math.min(t.length(), i + window + 1); j++) {
                if (!tMatched[j] && s.charAt(i) == t.charAt(j)) {
                    sMatched[i] = true;
                    tMatched[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0;
        }
        int transpositions = 0;
        for (int i = 0, j = 0; i < s.length(); i++) {
            if (sMatched[i]) {
                while (!tMatched[j]) {
                    j++;
                }
                if (s.charAt(i) != t.charAt(j)) {
                    transpositions++;
                }
                j++;
            }
        }
        double m = matches;
        double jaro = (m / s.length() + m / t.length() + (m - transpositions / 2.0) / m) / 3;
        int prefix = 0;
        while (prefix < Math.min(4, Math.min(s.length(), t.length())) && s.charAt(prefix) == t.charAt(prefix)) {
            prefix++;
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }
}
