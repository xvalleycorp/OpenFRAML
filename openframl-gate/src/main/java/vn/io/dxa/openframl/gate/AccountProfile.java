//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import java.time.LocalDate;
import java.util.Objects;

/**
 * What the gate knows about an account, precomputed outside the hot path.
 *
 * @param openedOn   the day the account was opened
 * @param riskRating the customer's AML risk rating (e.g. {@code LOW}, {@code HIGH}), or {@code null} if unrated
 */
public record AccountProfile(LocalDate openedOn, String riskRating) {

    public AccountProfile {
        Objects.requireNonNull(openedOn, "openedOn");
    }
}
