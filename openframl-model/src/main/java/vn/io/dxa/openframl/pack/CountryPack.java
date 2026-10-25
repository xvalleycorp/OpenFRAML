//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.pack;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Everything specific to one country, so the core never names a country: legal thresholds, decision tables, the
 * business time zone, the currency, and reason messages in the country's languages.
 *
 * <p>Packs are found with {@link ServiceLoader}: a pack lists its implementation in
 * {@code META-INF/services/vn.io.dxa.openframl.pack.CountryPack}. The core's own tests run against an invented
 * country, so an accidental dependency on a real one fails the build.
 */
public interface CountryPack {

    /** ISO 3166-1 alpha-2 code, e.g. {@code VN}; tests use the user-assigned codes {@code XA} to {@code XZ}. */
    String countryCode();

    /** The pack's own version, which changes whenever its content does. */
    String version();

    /** The zone that turns an instant into the business date, which selects the decision table version in force. */
    ZoneId businessZone();

    /** ISO 4217 code of the national currency. */
    String currency();

    /** Decision tables ({@code .stone.json}), including past versions so old decisions can be replayed. */
    List<PackResource> decisionTables();

    /**
     * The message for a reason code in a language, for investigators and reviewers. Not for customers: AML reasons
     * must not be disclosed to them.
     *
     * @param reasonCode a stable code such as {@code BENEFICIARY_ON_WATCHLIST}
     * @param locale     the language wanted; packs fall back to English
     * @return the message, or empty for a code the pack does not know
     */
    Optional<String> reasonMessage(String reasonCode, Locale locale);

    /** Every pack on the class path. */
    static List<CountryPack> installed() {
        return ServiceLoader.load(CountryPack.class).stream().map(ServiceLoader.Provider::get).toList();
    }

    /**
     * The installed pack for a country.
     *
     * @throws IllegalArgumentException if none, or more than one, is installed
     */
    static CountryPack forCountry(String countryCode) {
        List<CountryPack> packs = installed().stream().filter(pack -> pack.countryCode().equals(countryCode))
                .toList();
        if (packs.size() != 1) {
            throw new IllegalArgumentException(
                    "expected one country pack for " + countryCode + " on the class path, found " + packs.size());
        }
        return packs.getFirst();
    }
}
