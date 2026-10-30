//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.screen;

import java.util.List;
import java.util.Objects;

/**
 * One list, as published on one date.
 *
 * @param source  which list, e.g. {@code UN-SC} or the bank's own {@code BANK-PEP}
 * @param version the publication it was read from, recorded with every match
 * @param entries everyone on it
 */
public record ScreeningList(String source, String version, List<ListEntry> entries) {

    public ScreeningList {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(version, "version");
        entries = List.copyOf(entries);
    }
}
