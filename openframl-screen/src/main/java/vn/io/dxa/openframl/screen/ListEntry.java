//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.screen;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One listed person or organisation.
 *
 * @param id            the entry's id in its list, e.g. the UN reference number
 * @param kind          a person, an organisation, a vessel...
 * @param names         the primary name first, then every alias and spelling the list gives
 * @param birthYears    the possible years of birth of a person; a list often gives several, or none
 * @param nationalities as the list gives them: ISO 3166 codes in the open CSV form, country names in the UN list
 * @param identifiers   ID, passport or registration numbers, as listed
 * @param remark        what the list says about the entry, e.g. the sanctions programme
 */
public record ListEntry(
        String id,
        Kind kind,
        List<String> names,
        Set<Integer> birthYears,
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
        birthYears = Set.copyOf(birthYears);
        nationalities = Set.copyOf(nationalities);
        identifiers = Set.copyOf(identifiers);
        remark = remark == null ? "" : remark;
    }
}
