//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import java.util.Objects;

/**
 * The policy every gate node must run: the last approved change.
 *
 * @param sequence counts approved changes, from 1; a node applies a change once
 * @param change   from which policy to which, and who prepared and approved it
 */
public record ApprovedPolicy(long sequence, PolicyChange change) {

    public ApprovedPolicy {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        Objects.requireNonNull(change, "change");
    }
}
