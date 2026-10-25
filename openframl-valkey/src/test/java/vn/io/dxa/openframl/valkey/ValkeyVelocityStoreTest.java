//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.valkey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.io.dxa.openframl.gate.VelocitySnapshot;
import vn.io.dxa.openframl.model.AccountRef;
import vn.io.dxa.openframl.model.Amount;
import vn.io.dxa.openframl.model.Channel;
import vn.io.dxa.openframl.model.TransactionEvent;
import vn.io.dxa.openframl.model.TransactionType;

/** The parts of the velocity store that run in the gate's process; {@link ValkeyStoresTest} runs the rest. */
class ValkeyVelocityStoreTest {

    static final AccountRef ALICE = new AccountRef("999901", "0123456789");
    static final AccountRef BOB = new AccountRef("999902", "9876543210");
    static final AccountRef CAROL = new AccountRef("999902", "1111222233");
    static final Instant NOW = Instant.parse("2026-10-25T03:00:00Z");

    static TransactionEvent payment(String id, AccountRef to, String amount, String currency, Instant at) {
        return TransactionEvent.builder().eventId(id).occurredAt(at).channel(Channel.MOBILE)
                .type(TransactionType.TRANSFER_OUT).amount(Amount.of(amount, currency)).customerId("alice")
                .originator(ALICE).beneficiary(to).build();
    }

    private static void add(List<Object> reply, TransactionEvent event) {
        reply.add(ValkeyVelocityStore.member(event));
        reply.add(Long.toString(event.occurredAt().toEpochMilli()));
    }

    @Test
    void theStoredAttemptHoldsNoAccountNumber() {
        String member = ValkeyVelocityStore.member(payment("evt-1", BOB, "1500000", "VND", NOW));

        assertFalse(member.contains(BOB.account()), member);
        assertEquals(member, ValkeyVelocityStore.member(payment("evt-1", BOB, "1500000", "VND", NOW)));
    }

    @Test
    void countsWindowsTotalsAndBeneficiariesLikeTheInMemoryStore() {
        List<Object> reply = new ArrayList<>();
        add(reply, payment("evt-1", BOB, "100.25", "XTS", NOW.minusSeconds(3_600)));
        add(reply, payment("evt-2", CAROL, "200", "XTS", NOW.minusSeconds(60)));
        add(reply, payment("evt-3", BOB, "999", "XXX", NOW.minusSeconds(30)));
        add(reply, payment("evt-4", BOB, "0.75", "XTS", NOW));

        VelocitySnapshot snapshot = ValkeyVelocityStore.snapshot(reply, NOW.toEpochMilli(), Amount.of("0.75", "XTS"));

        assertEquals(3, snapshot.count10m());
        assertEquals(4, snapshot.count24h());
        assertEquals(Amount.of("301.00", "XTS"), snapshot.amount24h());
        assertEquals(2, snapshot.distinctBeneficiaries24h());
    }

    @Test
    void readsScoresInEitherNotation() {
        List<Object> reply = List.of(ValkeyVelocityStore.member(payment("evt-1", null, "1", "XTS", NOW)),
                "1.7928972e+12");

        VelocitySnapshot snapshot = ValkeyVelocityStore.snapshot(reply, NOW.toEpochMilli(), Amount.of(1, "XTS"));

        assertEquals(1, snapshot.count10m());
        assertEquals(0, snapshot.distinctBeneficiaries24h());
    }
}
