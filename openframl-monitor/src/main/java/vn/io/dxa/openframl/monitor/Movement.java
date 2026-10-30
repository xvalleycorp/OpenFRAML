//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.monitor;

import java.time.Instant;
import java.util.Objects;
import vn.io.dxa.openframl.model.Amount;

/**
 * Money in or out of one account, as monitoring keeps it: no account number, the counterparty only as a hash.
 *
 * @param eventId      the transaction; recording it twice changes nothing
 * @param at           when it happened
 * @param direction    into the account, or out of it
 * @param amount       how much
 * @param cash         a cash deposit or withdrawal
 * @param counterparty a hash of the counterparty account, or {@code null} for cash
 */
public record Movement(String eventId, Instant at, Direction direction, Amount amount, boolean cash,
        String counterparty) {

    public enum Direction {
        IN,
        OUT
    }

    public Movement {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
    }
}
