//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.model;

/** The channel a transaction was initiated on. */
public enum Channel {
    MOBILE,
    INTERNET,
    BRANCH,
    /** A third party through the bank's open API, e.g. a payment initiated by a fintech. */
    OPEN_API,
    CARD,
    ATM,
    OTHER
}
