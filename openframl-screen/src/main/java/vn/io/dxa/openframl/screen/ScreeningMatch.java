//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.screen;

import java.util.List;
import java.util.Objects;

/**
 * A listed entry that may be the subject, for an analyst to confirm or dismiss. Never a decision by itself.
 *
 * @param source      the list
 * @param listVersion the publication of the list
 * @param entry       the entry
 * @param matchedName the name or alias of the entry that came closest
 * @param score       from 0 to 1, after the adjustments in {@code reasons}
 * @param reasons     why it scored what it did, e.g. {@code NAME 0.93}, {@code BIRTH_YEAR_DIFFERS}
 */
public record ScreeningMatch(
        String source, String listVersion, ListEntry entry, String matchedName, double score, List<String> reasons) {

    public ScreeningMatch {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(listVersion, "listVersion");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(matchedName, "matchedName");
        reasons = List.copyOf(reasons);
    }
}
