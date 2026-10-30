//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.screen;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Who is being screened: a customer at onboarding, or the beneficiary of a payment.
 *
 * @param name        the name as the bank has it
 * @param kind        a person or an organisation, if known; other kinds are not compared
 * @param birthYear   for a person, if known
 * @param identifiers ID, passport or registration numbers, if known
 */
public record Subject(String name, Optional<ListEntry.Kind> kind, Optional<Integer> birthYear, Set<String> identifiers) {

    public Subject {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(birthYear, "birthYear");
        identifiers = Set.copyOf(identifiers);
    }

    /** Only a name, as on most payments. */
    public static Subject named(String name) {
        return new Subject(name, Optional.empty(), Optional.empty(), Set.of());
    }
}
