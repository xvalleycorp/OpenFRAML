//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * The rates that turn a foreign-currency amount into the national currency, for thresholds the law sets "or the
 * equivalent in foreign currency". The bank supplies its own rates, as its compliance rules say which apply.
 */
@FunctionalInterface
public interface ExchangeRates {

    /**
     * @return how much national currency one unit of {@code currency} is worth on {@code date}, or empty if there is
     *         no rate
     */
    Optional<BigDecimal> rate(String currency, LocalDate date);

    /** No rates: only national-currency amounts are compared with the thresholds. */
    static ExchangeRates none() {
        return (currency, date) -> Optional.empty();
    }
}
