//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.screen;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Names reduced to the form they are compared in: no diacritics, lower case, letters and digits only, split into
 * tokens. "Nguyễn Văn Đức" and "NGUYEN VAN DUC" become the same three tokens.
 *
 * <p>Business abbreviations common in Vietnamese company names are written out ("TM" is "thuong mai"), so that
 * "Cong ty TNHH TM Hoang Long" and "Công ty TNHH Thương mại Hoàng Long" compare equal.
 */
public final class Names {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");

    private static final Map<String, List<String>> ABBREVIATIONS = Map.of(
            "tm", List.of("thuong", "mai"),
            "dv", List.of("dich", "vu"),
            "sx", List.of("san", "xuat"),
            "xnk", List.of("xuat", "nhap", "khau"),
            "xd", List.of("xay", "dung"),
            "dt", List.of("dau", "tu"),
            "cp", List.of("co", "phan"));

    /** Legal forms, left out when the core of an organisation's name is compared. */
    private static final Set<String> LEGAL_FORMS = Set.of("cong", "ty", "tnhh", "co", "phan", "mtv", "jsc", "ltd",
            "llc", "inc", "corp", "corporation", "company", "limited", "plc", "gmbh", "group", "tap", "doan");

    private Names() {}

    /** The comparable form of {@code name}, tokens separated by one space. */
    public static String normalize(String name) {
        // đ and Đ are letters of their own, not d with a mark: NFKD leaves them as they are
        String plain = name.replace('đ', 'd').replace('Đ', 'D');
        String stripped = MARKS.matcher(Normalizer.normalize(plain, Normalizer.Form.NFKD)).replaceAll("");
        return SEPARATORS.matcher(stripped.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    public static List<String> tokens(String name) {
        String normalized = normalize(name);
        List<String> tokens = new ArrayList<>();
        if (normalized.isEmpty()) {
            return tokens;
        }
        for (String token : normalized.split(" ")) {
            tokens.addAll(ABBREVIATIONS.getOrDefault(token, List.of(token)));
        }
        return List.copyOf(tokens);
    }

    /** {@code tokens} without legal forms such as "cong ty", "TNHH" or "Ltd"; for organisations only. */
    public static List<String> core(List<String> tokens) {
        return tokens.stream().filter(token -> !LEGAL_FORMS.contains(token)).toList();
    }
}
