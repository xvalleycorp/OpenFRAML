//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

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
}
