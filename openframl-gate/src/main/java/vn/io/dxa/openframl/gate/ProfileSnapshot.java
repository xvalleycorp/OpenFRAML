//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import java.util.Objects;
import java.util.Optional;

/**
 * Everything the gate reads about the parties of one payment.
 *
 * @param originator       the originating account, if the store has it
 * @param beneficiary      the beneficiary account, if the store has it (usually only for accounts at this bank, or
 *                         accounts profiled from incoming payments)
 * @param beneficiaryKnown the originator has paid this beneficiary before
 * @param deviceKnown      the customer has used this device before; ignored when the session has no device id
 */
public record ProfileSnapshot(
        Optional<AccountProfile> originator,
        Optional<AccountProfile> beneficiary,
        boolean beneficiaryKnown,
        boolean deviceKnown) {

    public ProfileSnapshot {
        Objects.requireNonNull(originator, "originator");
        Objects.requireNonNull(beneficiary, "beneficiary");
    }
}
