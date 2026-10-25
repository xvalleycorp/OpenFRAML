//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

/** Why the gate fell back to its fallback policy instead of the decision table. */
public enum GateFailure {
    /** The caller's deadline passed before the decision table could run. */
    TIMEOUT,
    /** The profile or velocity store failed. */
    STORE_UNAVAILABLE,
    /** The decision table failed or produced no action. */
    POLICY_ERROR
}
