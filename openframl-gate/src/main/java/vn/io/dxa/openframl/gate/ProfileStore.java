//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import vn.io.dxa.openframl.model.TransactionEvent;

/**
 * Reads precomputed profiles, kept up to date by the stream layer. Implementations answer from memory or a local
 * key-value store; never from a relational database.
 */
@FunctionalInterface
public interface ProfileStore {

    /**
     * Everything the gate needs about one payment's parties, in one call, so a remote store answers in a single
     * round trip.
     *
     * @throws RuntimeException if the store is unavailable; the gate then applies its fallback policy
     */
    ProfileSnapshot lookup(TransactionEvent event);
}
