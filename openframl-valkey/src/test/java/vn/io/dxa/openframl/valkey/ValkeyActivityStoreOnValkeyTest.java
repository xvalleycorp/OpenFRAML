//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.valkey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.io.dxa.openframl.valkey.ValkeyActivityStoreTest.NOW;
import static vn.io.dxa.openframl.valkey.ValkeyActivityStoreTest.movement;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import vn.io.dxa.openframl.monitor.Movement;

/** Runs against a real Valkey, e.g. {@code OPENFRAML_TEST_VALKEY=redis://localhost:6379}; CI starts one. */
@EnabledIfEnvironmentVariable(named = "OPENFRAML_TEST_VALKEY", matches = ".+")
class ValkeyActivityStoreOnValkeyTest {

    private static Valkey valkey;

    @BeforeAll
    static void connect() {
        valkey = Valkey.connect(System.getenv("OPENFRAML_TEST_VALKEY"), Duration.ofSeconds(2));
    }

    @AfterAll
    static void close() {
        if (valkey != null) {
            valkey.close();
        }
    }

    private final ValkeyActivityStore store = new ValkeyActivityStore(valkey.connection(),
            new ValkeyLayout("ofr-test-" + UUID.randomUUID() + ":"), Duration.ofDays(35));

    @Test
    void movementsAreRecordedOnceAndOldOnesDropped() {
        String account = "999901/0123456789";
        assertEquals(Optional.empty(), store.lastActivity(account));

        assertTrue(store.add(account, movement("old", NOW.minus(Duration.ofDays(40)), "aa")));
        assertTrue(store.add(account, movement("evt-1", NOW.minus(Duration.ofDays(3)), "bb")));
        assertTrue(store.add(account, movement("evt-2", NOW, null)));
        assertFalse(store.add(account, movement("evt-2", NOW, null)), "a movement is recorded once");

        assertTrue(store.contains(account, "evt-1"));
        assertFalse(store.contains(account, "old"), "older than the retention");
        assertEquals(Optional.of(NOW), store.lastActivity(account));
        assertEquals(List.of("evt-1", "evt-2"), store.since(account, NOW.minus(Duration.ofDays(30))).stream()
                .map(Movement::eventId).toList());
        assertEquals(List.of("evt-2"), store.since(account, NOW).stream().map(Movement::eventId).toList());
        assertEquals(List.of(), store.since("999901/other", NOW.minus(Duration.ofDays(30))));
    }
}
