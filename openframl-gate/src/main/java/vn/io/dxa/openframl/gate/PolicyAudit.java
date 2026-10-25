//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

/**
 * Receives every policy change before it takes effect. If it throws, the change is not applied: no policy is ever
 * in force without its change having been recorded.
 */
@FunctionalInterface
public interface PolicyAudit {

    /** Records nothing; for tests only. */
    PolicyAudit NONE = change -> {};

    void record(PolicyChange change);
}
