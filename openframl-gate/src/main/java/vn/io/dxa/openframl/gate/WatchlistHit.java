//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import java.util.Objects;

/**
 * An account found on a list of suspicious accounts.
 *
 * @param source      the list, e.g. the regulator's list of suspicious accounts or the bank's own
 * @param listVersion the version of the list the account was found in, for the audit trail
 */
public record WatchlistHit(String source, String listVersion) {

    public WatchlistHit {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(listVersion, "listVersion");
    }
}
