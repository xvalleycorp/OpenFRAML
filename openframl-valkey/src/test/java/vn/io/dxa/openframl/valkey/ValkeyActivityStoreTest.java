//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.valkey;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import vn.io.dxa.openframl.model.Amount;
import vn.io.dxa.openframl.monitor.Movement;

class ValkeyActivityStoreTest {

    static final Instant NOW = Instant.parse("2026-10-30T03:00:00Z");

    static Movement movement(String id, Instant at, String counterparty) {
        return new Movement(id, at, Movement.Direction.IN, Amount.of("1500000.50", "VND"), counterparty == null,
                counterparty);
    }

    @Test
    void aMovementIsStoredWithEveryField() {
        Movement cash = movement("evt-1", NOW, null);
        Movement transfer = new Movement("evt-2", NOW, Movement.Direction.OUT, Amount.of(7, "USD"), false, "ab12");

        assertEquals(cash, ValkeyActivityStore.decode(ValkeyActivityStore.encode(cash)));
        assertEquals(transfer, ValkeyActivityStore.decode(ValkeyActivityStore.encode(transfer)));
    }
}
