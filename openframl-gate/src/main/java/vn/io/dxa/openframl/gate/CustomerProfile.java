//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import java.time.LocalDate;
import java.util.Optional;

/**
 * What the gate knows about a customer, precomputed outside the hot path, for rules that depend on the customer
 * rather than the account, such as a customer identification trigger.
 *
 * @param hasAccount        the customer holds at least one account at the bank
 * @param lastTransactionOn the business day of the customer's last transaction before today, on any account; for an
 *                          account never used, the day it was opened; {@code null} if the customer has no account
 */
public record CustomerProfile(boolean hasAccount, LocalDate lastTransactionOn) {

    public Optional<LocalDate> lastTransaction() {
        return Optional.ofNullable(lastTransactionOn);
    }
}
