//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import java.util.Objects;
import vn.io.dxa.openframl.model.Amount;

/**
 * Short-term activity of the originating account, including the payment being decided.
 *
 * @param count10m                 payment attempts in the last 10 minutes
 * @param count24h                 payment attempts in the last 24 hours
 * @param amount24h                their total, in the currency of the payment being decided
 * @param distinctBeneficiaries24h distinct beneficiaries in the last 24 hours
 */
public record VelocitySnapshot(long count10m, long count24h, Amount amount24h, long distinctBeneficiaries24h) {

    public VelocitySnapshot {
        Objects.requireNonNull(amount24h, "amount24h");
    }
}
