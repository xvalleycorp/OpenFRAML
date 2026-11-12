//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import java.util.Objects;
import java.util.Optional;
import vn.io.dxa.openframl.model.Amount;

/**
 * Short-term activity of the originating account, including the payment being decided.
 *
 * @param count10m                 payment attempts in the last 10 minutes
 * @param count24h                 payment attempts in the last 24 hours
 * @param amount24h                their total, in the currency of the payment being decided
 * @param distinctBeneficiaries24h distinct beneficiaries in the last 24 hours
 * @param customerToday            the total of the customer's attempts on the business day, on every account, in the
 *                                 currency of the payment being decided; empty if the store does not keep it
 */
public record VelocitySnapshot(long count10m, long count24h, Amount amount24h, long distinctBeneficiaries24h,
        Optional<Amount> customerToday) {

    public VelocitySnapshot {
        Objects.requireNonNull(amount24h, "amount24h");
        Objects.requireNonNull(customerToday, "customerToday");
    }

    /** A snapshot without the customer's total for the day. */
    public VelocitySnapshot(long count10m, long count24h, Amount amount24h, long distinctBeneficiaries24h) {
        this(count10m, count24h, amount24h, distinctBeneficiaries24h, Optional.empty());
    }

    /** This snapshot with the customer's total for the day. */
    public VelocitySnapshot withCustomerToday(Amount total) {
        return new VelocitySnapshot(count10m, count24h, amount24h, distinctBeneficiaries24h, Optional.of(total));
    }
}
