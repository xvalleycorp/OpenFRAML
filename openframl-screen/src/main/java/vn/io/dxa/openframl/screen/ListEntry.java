//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.screen;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One listed person or organisation.
 *
 * @param id            the entry's id in its list, e.g. the UN reference number
 * @param kind          a person, an organisation, a vessel...
 * @param names         the primary name first, then every alias and spelling the list gives
 * @param birthYear     the year of birth, for a person, if the list gives one
 * @param nationalities ISO 3166 country codes
 * @param identifiers   ID, passport or registration numbers, as listed
 * @param remark        what the list says about the entry, e.g. the sanctions programme
 */
public record ListEntry(
        String id,
        Kind kind,
        List<String> names,
        Optional<Integer> birthYear,
        Set<String> nationalities,
        Set<String> identifiers,
        String remark) {

    public enum Kind {
        PERSON,
        ORGANISATION,
        VESSEL,
        OTHER
    }

    public ListEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        names = List.copyOf(names);
        if (names.isEmpty() || names.stream().allMatch(name -> Names.normalize(name).isEmpty())) {
            throw new IllegalArgumentException("entry " + id + " has no name");
        }
        Objects.requireNonNull(birthYear, "birthYear");
        nationalities = Set.copyOf(nationalities);
        identifiers = Set.copyOf(identifiers);
        remark = remark == null ? "" : remark;
    }
}
