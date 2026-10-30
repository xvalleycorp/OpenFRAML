//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.cases.server;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import vn.io.dxa.openframl.cases.CaseService.Investigator;
import vn.io.dxa.openframl.cases.CaseService.Role;

/**
 * Who may do what, by certificate common name, as the bank configures it. Names compare without regard to case.
 *
 * @param analysts    investigate and propose conclusions
 * @param supervisors assign cases and approve or return conclusions
 * @param detectors   systems that raise alerts
 */
public record CaseDirectory(Set<String> analysts, Set<String> supervisors, Set<String> detectors) {

    public CaseDirectory {
        analysts = normalise(analysts);
        supervisors = normalise(supervisors);
        detectors = normalise(detectors);
    }

    /** {@code name} with the roles the bank gives it; none if it is not listed. */
    public Investigator investigator(String name) {
        String key = key(name);
        Set<Role> roles = EnumSet.noneOf(Role.class);
        if (analysts.contains(key)) {
            roles.add(Role.ANALYST);
        }
        if (supervisors.contains(key)) {
            roles.add(Role.SUPERVISOR);
        }
        return new Investigator(name, roles);
    }

    public boolean isDetector(String name) {
        return detectors.contains(key(name));
    }

    private static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    private static Set<String> normalise(Set<String> names) {
        return names.stream().map(String::strip).filter(name -> !name.isEmpty()).map(CaseDirectory::key)
                .collect(Collectors.toUnmodifiableSet());
    }
}
