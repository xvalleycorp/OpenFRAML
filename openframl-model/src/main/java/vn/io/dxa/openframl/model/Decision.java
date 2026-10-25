//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.model;

/**
 * What the bank should do with a payment before it is sent, from least to most severe.
 *
 * <p>The order matters: when several rules fire, the most severe decision wins ({@link #max(Decision)}).
 */
public enum Decision {
    /** Send the payment. */
    ALLOW,
    /** Ask for more: show a scam warning, re-authenticate, or have the customer confirm again. */
    CHALLENGE,
    /** Keep the payment back for a quick review, and open a high-priority case. */
    HOLD,
    /** Refuse the payment. */
    DENY;

    /** The more severe of this decision and {@code other}. */
    public Decision max(Decision other) {
        return other.compareTo(this) > 0 ? other : this;
    }
}
