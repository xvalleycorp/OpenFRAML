//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.screen;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Names reduced to the form they are compared in: no diacritics, lower case, letters and digits only, tokens
 * separated by one space. "Nguyễn Văn Đức" and "NGUYEN VAN DUC" become the same. How the result is split into tokens,
 * with a country's abbreviations and legal forms, is {@link NameRules}.
 */
public final class Names {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");

    private Names() {}

    /** The comparable form of {@code name}, tokens separated by one space. */
    public static String normalize(String name) {
        // đ and Đ are letters of their own, not d with a mark: NFKD leaves them as they are
        String plain = name.replace('đ', 'd').replace('Đ', 'D');
        String stripped = MARKS.matcher(Normalizer.normalize(plain, Normalizer.Form.NFKD)).replaceAll("");
        return SEPARATORS.matcher(stripped.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }
}
