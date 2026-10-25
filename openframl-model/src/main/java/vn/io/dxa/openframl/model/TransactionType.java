//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.model;

/** What a transaction does, seen from the originating account. */
public enum TransactionType {
    TRANSFER_OUT,
    TRANSFER_IN,
    CASH_DEPOSIT,
    CASH_WITHDRAWAL,
    CARD_PAYMENT,
    BILL_PAYMENT
}
