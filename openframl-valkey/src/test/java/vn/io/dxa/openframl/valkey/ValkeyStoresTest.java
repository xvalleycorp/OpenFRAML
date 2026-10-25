//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.valkey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.io.dxa.openframl.valkey.ValkeyVelocityStoreTest.ALICE;
import static vn.io.dxa.openframl.valkey.ValkeyVelocityStoreTest.BOB;
import static vn.io.dxa.openframl.valkey.ValkeyVelocityStoreTest.CAROL;
import static vn.io.dxa.openframl.valkey.ValkeyVelocityStoreTest.NOW;
import static vn.io.dxa.openframl.valkey.ValkeyVelocityStoreTest.payment;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import vn.io.dxa.openframl.gate.AccountProfile;
import vn.io.dxa.openframl.gate.ProfileSnapshot;
import vn.io.dxa.openframl.gate.VelocitySnapshot;
import vn.io.dxa.openframl.gate.memory.InMemoryVelocityStore;
import vn.io.dxa.openframl.model.AccountRef;
import vn.io.dxa.openframl.model.SessionSignals;
import vn.io.dxa.openframl.model.TransactionEvent;

/**
 * Runs against a real Valkey, e.g. {@code OPENFRAML_TEST_VALKEY=redis://localhost:6379}; CI starts one. Each test
 * writes under its own key prefix.
 */
@EnabledIfEnvironmentVariable(named = "OPENFRAML_TEST_VALKEY", matches = ".+")
class ValkeyStoresTest {

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

    private static ValkeyLayout layout() {
        return new ValkeyLayout("ofr-test:" + UUID.randomUUID() + ":");
    }

    private static TransactionEvent withDevice(TransactionEvent event, String deviceId) {
        return TransactionEvent.builder().eventId(event.eventId()).occurredAt(event.occurredAt())
                .channel(event.channel()).type(event.type()).amount(event.amount()).customerId(event.customerId())
                .originator(event.originator()).beneficiary(event.beneficiary())
                .session(new SessionSignals(deviceId, "192.0.2.10", false, false, "BIOMETRIC")).build();
    }

    @Test
    void readsWhatTheLoaderWrote() {
        ValkeyLayout layout = layout();
        new ValkeyProfileLoader(valkey.connection(), layout)
                .account(ALICE, new AccountProfile(LocalDate.of(2020, 1, 15), "LOW"))
                .account(BOB, new AccountProfile(LocalDate.of(2026, 10, 20), null))
                .knownBeneficiary(ALICE, BOB)
                .knownDevice("alice", "phone-1");
        ValkeyProfileStore store = new ValkeyProfileStore(valkey.connection(), layout, Duration.ofMinutes(1), 1_000);

        ProfileSnapshot known = store.lookup(withDevice(payment("evt-1", BOB, "1", "XTS", NOW), "phone-1"));
        assertEquals(Optional.of(new AccountProfile(LocalDate.of(2020, 1, 15), "LOW")), known.originator());
        assertEquals(Optional.of(new AccountProfile(LocalDate.of(2026, 10, 20), null)), known.beneficiary());
        assertTrue(known.beneficiaryKnown());
        assertTrue(known.deviceKnown());

        ProfileSnapshot unknown = store.lookup(withDevice(payment("evt-2", CAROL, "1", "XTS", NOW), "phone-2"));
        assertEquals(Optional.empty(), unknown.beneficiary());
        assertFalse(unknown.beneficiaryKnown());
        assertFalse(unknown.deviceKnown());
    }

    @Test
    void theCacheAnswersUntilItIsInvalidated() {
        ValkeyLayout layout = layout();
        ValkeyProfileLoader loader = new ValkeyProfileLoader(valkey.connection(), layout);
        ValkeyProfileStore store = new ValkeyProfileStore(valkey.connection(), layout, Duration.ofMinutes(1), 1_000);
        TransactionEvent event = payment("evt-1", BOB, "1", "XTS", NOW);

        assertEquals(Optional.empty(), store.lookup(event).originator());
        loader.account(ALICE, new AccountProfile(LocalDate.of(2020, 1, 15), "HIGH")).knownBeneficiary(ALICE, BOB);
        assertEquals(Optional.empty(), store.lookup(event).originator(), "absent profile is cached");
        assertTrue(store.lookup(event).beneficiaryKnown(), "unknown beneficiary is not cached");

        store.invalidateCache();
        assertEquals("HIGH", store.lookup(event).originator().orElseThrow().riskRating());
    }

    @Test
    void agreesWithTheInMemoryStore() {
        ValkeyVelocityStore valkeyStore = new ValkeyVelocityStore(valkey.connection(), layout(), 50);
        InMemoryVelocityStore memory = new InMemoryVelocityStore(50);
        Random random = new Random(42);
        List<AccountRef> payees = List.of(BOB, CAROL, new AccountRef("999903", "4444"));
        Instant at = NOW;
        for (int i = 0; i < 300; i++) {
            at = at.plusSeconds(random.nextInt(900));
            TransactionEvent event = payment("evt-" + i, payees.get(random.nextInt(payees.size())),
                    random.nextInt(1_000_000) + "." + random.nextInt(100), random.nextInt(10) == 0 ? "XXX" : "XTS",
                    at);
            assertEquals(memory.recordAttempt(event), valkeyStore.recordAttempt(event), "attempt " + i);
        }
    }

    @Test
    void aRetriedCallIsCountedOnce() {
        ValkeyVelocityStore store = new ValkeyVelocityStore(valkey.connection(), layout(), 10_000);
        TransactionEvent event = payment("evt-1", BOB, "100", "XTS", NOW);

        store.recordAttempt(event);
        VelocitySnapshot retried = store.recordAttempt(event);

        assertEquals(1, retried.count24h());
    }

    @Test
    void attemptsFromManyNodesSeeEachOther() throws Exception {
        ValkeyLayout layout = layout();
        List<Callable<VelocitySnapshot>> nodes = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            TransactionEvent event = payment("evt-" + i, BOB, "1", "XTS", NOW.plusMillis(i));
            ValkeyVelocityStore node = new ValkeyVelocityStore(valkey.connection(), layout, 10_000);
            nodes.add(() -> node.recordAttempt(event));
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            long max = 0;
            for (Future<VelocitySnapshot> result : pool.invokeAll(nodes)) {
                max = Math.max(max, result.get().count24h());
            }
            assertEquals(200, max);
        }
    }
}
