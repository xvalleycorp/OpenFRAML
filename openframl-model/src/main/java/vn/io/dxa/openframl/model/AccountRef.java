//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.model;

import java.util.Objects;

/**
 * An account at an institution.
 *
 * <p>{@link #toString()} masks the account number, so a reference that ends up in a log message does not leak it.
 * Use {@link #key()} only as a lookup key, never in logs or decision records.
 *
 * @param institution the institution's identifier in the country's payment scheme (bank code, BIN or BIC)
 * @param account     the account number, as the institution writes it
 */
public record AccountRef(String institution, String account) {

    public AccountRef {
        requireText(institution, "institution");
        requireText(account, "account");
    }

    /** {@code <institution>/<account>}, unique across institutions; a lookup key for stores and indexes. */
    public String key() {
        return institution + '/' + account;
    }

    /** The institution and the last four characters of the account, e.g. {@code 999901/****6789}. */
    @Override
    public String toString() {
        return institution + "/" + mask(account);
    }

    static String mask(String account) {
        return account.length() <= 4 ? "****" : "****" + account.substring(account.length() - 4);
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
