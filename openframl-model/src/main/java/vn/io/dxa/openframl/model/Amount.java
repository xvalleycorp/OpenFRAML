//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A non-negative amount of money in one currency.
 *
 * <p>The value is kept exactly as given (no rounding); the decision engine compares it exactly. The model does not
 * fix a currency: each country pack names its own.
 *
 * @param value    the amount, never negative
 * @param currency upper-case ISO 4217 code, e.g. {@code EUR}
 */
public record Amount(BigDecimal value, String currency) {

    public Amount {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(currency, "currency");
        if (value.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative: " + value);
        }
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be an upper-case ISO 4217 code: " + currency);
        }
    }

    public static Amount of(String value, String currency) {
        return new Amount(new BigDecimal(value), currency);
    }

    public static Amount of(long value, String currency) {
        return new Amount(BigDecimal.valueOf(value), currency);
    }

    @Override
    public String toString() {
        return value.toPlainString() + " " + currency;
    }
}
