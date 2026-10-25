//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import java.time.ZoneOffset;
import java.util.List;
import vn.io.dxa.openframl.pack.ResourceCountryPack;

/**
 * An invented country, XA, with the ISO 4217 test currency XTS: the gate's tests never touch a real country, so a
 * dependency of the core on one shows up as a failing test.
 */
final class TestPack extends ResourceCountryPack {

    static final String CURRENCY = "XTS";

    TestPack(String... tables) {
        super("XA", "test-1", ZoneOffset.UTC, CURRENCY, TestPack.class,
                List.of(tables).stream().map(table -> "xa/" + table + ".stone.json").toList(), "xa/reasons");
    }

    /** The standard test tables. */
    static TestPack standard() {
        return new TestPack("gate_decision.v1", "gate_fallback.v1");
    }
}
