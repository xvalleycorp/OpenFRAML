//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate.memory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import vn.io.dxa.openframl.gate.Watchlist;
import vn.io.dxa.openframl.gate.WatchlistHit;
import vn.io.dxa.openframl.model.AccountRef;

/**
 * A local replica of the lists of suspicious accounts, replaced as a whole when a list changes: a lookup sees
 * either the old snapshot or the new one, never a mix.
 */
public final class InMemoryWatchlist implements Watchlist {

    private volatile Map<String, WatchlistHit> entries = Map.of();

    @Override
    public Optional<WatchlistHit> find(AccountRef account) {
        return Optional.ofNullable(entries.get(account.key()));
    }

    /** Replaces every entry at once. */
    public void replace(Map<AccountRef, WatchlistHit> snapshot) {
        Map<String, WatchlistHit> byKey = new HashMap<>(snapshot.size() * 2);
        snapshot.forEach((account, hit) -> byKey.put(account.key(), hit));
        entries = Map.copyOf(byKey);
    }

    public int size() {
        return entries.size();
    }
}
