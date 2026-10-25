//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import java.util.Optional;
import vn.io.dxa.openframl.model.AccountRef;

/**
 * Local replica of the lists of suspicious accounts. The gate checks it first, before anything that could fail or
 * time out, so a listed beneficiary is held even when the rest of the gate is unavailable.
 */
@FunctionalInterface
public interface Watchlist {

    /** No list. */
    Watchlist NONE = account -> Optional.empty();

    Optional<WatchlistHit> find(AccountRef account);
}
