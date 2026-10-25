//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The gate names no country: no currency, time zone, regulator, scheme or legal threshold. Those belong in a country
 * pack. This test reads the sources, so a slip is caught even in code the other tests do not reach.
 */
class CountryNeutralityTest {

    private static final List<String> COUNTRY_SPECIFIC = List.of(
            "vnd", "ho_chi_minh", "napas", "simo", "nhnn", "vietqr", "\"vn\"", "vietnam");

    @Test
    void mainSourcesNameNoCountry() throws IOException {
        List<String> found;
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            found = files.filter(Files::isRegularFile).flatMap(CountryNeutralityTest::matches).toList();
        }
        assertEquals(List.of(), found);
    }

    private static Stream<String> matches(Path file) {
        String text;
        try {
            text = Files.readString(file).toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return COUNTRY_SPECIFIC.stream().filter(text::contains).map(word -> file + ": " + word);
    }
}
