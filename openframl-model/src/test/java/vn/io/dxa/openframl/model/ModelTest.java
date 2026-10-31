//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ModelTest {

    @Test
    void accountNumbersAreMaskedWhenPrinted() {
        AccountRef ref = new AccountRef("999901", "0123456789");
        assertEquals("999901/****6789", ref.toString());
        assertEquals("999901/0123456789", ref.key());
        assertEquals("999901/****", new AccountRef("999901", "789").toString());

        TransactionEvent event = event().beneficiary(ref).build();
        assertFalse(event.toString().contains("0123456789"), event::toString);
    }

    @Test
    void amountsAreNonNegativeWithAnIsoCurrency() {
        assertEquals("1500.50 XTS", Amount.of("1500.50", "XTS").toString());
        assertThrows(IllegalArgumentException.class, () -> Amount.of(-1, "XTS"));
        assertThrows(IllegalArgumentException.class, () -> Amount.of(1, "xts"));
        assertThrows(IllegalArgumentException.class, () -> Amount.of(1, "XT"));
    }

    @Test
    void theMostSevereDecisionWins() {
        assertEquals(Decision.HOLD, Decision.ALLOW.max(Decision.HOLD));
        assertEquals(Decision.HOLD, Decision.HOLD.max(Decision.CHALLENGE));
        assertEquals(Decision.DENY, Decision.DENY.max(Decision.DENY));
    }

    @Test
    void eventsOfAnotherSchemaVersionAreRejected() {
        TransactionEvent event = event().build();
        assertSame(SessionSignals.NONE, event.session());
        assertThrows(IllegalArgumentException.class, () -> new TransactionEvent("2", event.eventId(),
                event.occurredAt(), event.channel(), event.type(), event.amount(), event.customerId(),
                event.originator(), event.beneficiary(), event.session(), null, null));
    }

    @Test
    void theCounterpartysCountryIsAnIsoCode() {
        assertEquals("SG", event().counterpartyCountry(" SG ").build().counterpartyCountry());
        assertNull(event().counterpartyCountry("").build().counterpartyCountry());
        assertThrows(IllegalArgumentException.class, () -> event().counterpartyCountry("sg").build());
        assertThrows(IllegalArgumentException.class, () -> event().counterpartyCountry("SGP").build());
    }

    @Test
    void theBeneficiarysNameIsKeptButNeverPrinted() {
        TransactionEvent event = event().beneficiaryName("  Nguyễn Văn Đức ").build();

        assertEquals("Nguyễn Văn Đức", event.beneficiaryName());
        assertFalse(event.toString().contains("Nguy"), event::toString);
        assertTrue(event.toString().contains("beneficiaryName=redacted"), event::toString);
        assertNull(event().beneficiaryName(" ").build().beneficiaryName());
    }

    private static TransactionEvent.Builder event() {
        return TransactionEvent.builder().eventId("evt-1").occurredAt(Instant.parse("2026-10-25T03:00:00Z"))
                .channel(Channel.MOBILE).type(TransactionType.TRANSFER_OUT).amount(Amount.of(100, "XTS"))
                .customerId("cust-1").originator(new AccountRef("999901", "1111222233"));
    }
}
