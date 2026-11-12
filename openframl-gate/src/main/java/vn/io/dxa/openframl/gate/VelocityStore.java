//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import java.time.LocalDate;
import vn.io.dxa.openframl.model.TransactionEvent;

/**
 * Short-term counters, updated inside the gate rather than by the stream layer, because a fraudster's payments
 * follow each other within seconds.
 */
@FunctionalInterface
public interface VelocityStore {

    /**
     * Counts this payment attempt and returns the counters including it. Must be consistent per originating
     * account: the next call for the same account sees this one. (Requests are routed to nodes by account, so a
     * node-local store is enough.)
     *
     * @throws RuntimeException if the store is unavailable; the gate then applies its fallback policy
     */
    VelocitySnapshot recordAttempt(TransactionEvent event);

    /**
     * Counts this payment attempt, as {@link #recordAttempt(TransactionEvent)} does, and also adds it to the customer's
     * total for the business day, which the snapshot then carries. The gate calls this one. A store that does not keep
     * customers' totals leaves the snapshot without one, which is the default.
     *
     * @param businessDate the payment's business day, in the country pack's zone
     */
    default VelocitySnapshot recordAttempt(TransactionEvent event, LocalDate businessDate) {
        return recordAttempt(event);
    }
}
