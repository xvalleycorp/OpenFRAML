//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.io.dxa.openframl.gate.VelocitySnapshot;
import vn.io.dxa.openframl.model.AccountRef;
import vn.io.dxa.openframl.model.Amount;
import vn.io.dxa.openframl.model.Channel;
import vn.io.dxa.openframl.model.TransactionEvent;
import vn.io.dxa.openframl.model.TransactionType;

class InMemoryVelocityStoreTest {

    private static final AccountRef FROM = new AccountRef("999901", "0123456789");
    private static final Instant T0 = Instant.parse("2026-10-25T00:00:00Z");

    private static TransactionEvent payment(Instant at, long amount, String currency, String toAccount) {
        return TransactionEvent.builder().eventId(at.toString()).occurredAt(at).channel(Channel.MOBILE)
                .type(TransactionType.TRANSFER_OUT).amount(Amount.of(amount, currency)).customerId("c1")
                .originator(FROM).beneficiary(new AccountRef("999902", toAccount)).build();
    }

    @Test
    void countsTheAttemptBeingDecidedAndThoseBeforeIt() {
        InMemoryVelocityStore store = new InMemoryVelocityStore();
        store.recordAttempt(payment(T0, 100, "XTS", "1111"));
        store.recordAttempt(payment(T0.plus(Duration.ofMinutes(5)), 200, "XTS", "2222"));
        VelocitySnapshot third = store.recordAttempt(payment(T0.plus(Duration.ofMinutes(12)), 300, "XTS", "1111"));

        assertEquals(new VelocitySnapshot(2, 3, Amount.of(600, "XTS"), 2), third);
    }

    @Test
    void windowsEndExactlyTenMinutesAndTwentyFourHoursBack() {
        InMemoryVelocityStore store = new InMemoryVelocityStore();
        store.recordAttempt(payment(T0, 100, "XTS", "1111"));
        VelocitySnapshot atTenMinutes = store.recordAttempt(payment(T0.plus(Duration.ofMinutes(10)), 1, "XTS", "1"));
        assertEquals(1, atTenMinutes.count10m());
        assertEquals(2, atTenMinutes.count24h());

        VelocitySnapshot atOneDay = store.recordAttempt(payment(T0.plus(Duration.ofHours(24)), 1, "XTS", "1"));
        assertEquals(2, atOneDay.count24h());
        assertEquals(Amount.of(2, "XTS"), atOneDay.amount24h());
    }

    @Test
    void sumsOnlyTheCurrencyOfThePaymentBeingDecided() {
        InMemoryVelocityStore store = new InMemoryVelocityStore();
        store.recordAttempt(payment(T0, 100, "EUR", "1111"));
        VelocitySnapshot snapshot = store.recordAttempt(payment(T0.plusSeconds(1), 7, "XTS", "1111"));
        assertEquals(Amount.of(7, "XTS"), snapshot.amount24h());
        assertEquals(2, snapshot.count24h());
    }

    @Test
    void aBusyAccountSaturatesAtTheCap() {
        InMemoryVelocityStore store = new InMemoryVelocityStore(3);
        VelocitySnapshot last = null;
        for (int i = 0; i < 10; i++) {
            last = store.recordAttempt(payment(T0.plusSeconds(i), 1, "XTS", "1111"));
        }
        assertEquals(3, last.count24h());
    }

    @Test
    void pruneForgetsIdleAccounts() {
        InMemoryVelocityStore store = new InMemoryVelocityStore();
        store.recordAttempt(payment(T0, 1, "XTS", "1111"));
        store.prune(T0.plus(Duration.ofHours(23)));
        assertEquals(1, store.accounts());
        store.prune(T0.plus(Duration.ofHours(24)));
        assertEquals(0, store.accounts());
    }

    @Test
    void keepsTheCustomersTotalForTheBusinessDayOnEveryAccount() {
        InMemoryVelocityStore store = new InMemoryVelocityStore();
        LocalDate day = LocalDate.of(2026, 10, 25);
        TransactionEvent first = payment(T0, 100, "XTS", "1111");
        TransactionEvent otherAccount = TransactionEvent.builder().eventId("other").occurredAt(T0.plusSeconds(60))
                .channel(Channel.BRANCH).type(TransactionType.CASH_WITHDRAWAL).amount(Amount.of(250, "XTS"))
                .customerId("c1").originator(new AccountRef("999901", "9999")).build();

        assertEquals(Optional.of(Amount.of(100, "XTS")), store.recordAttempt(first, day).customerToday());
        assertEquals(Optional.of(Amount.of(350, "XTS")), store.recordAttempt(otherAccount, day).customerToday());
        assertEquals(Optional.of(Amount.of(350, "XTS")), store.recordAttempt(otherAccount, day).customerToday(),
                "a retried call is counted once");
        assertEquals(Optional.of(Amount.of(7, "XXX")),
                store.recordAttempt(payment(T0.plusSeconds(120), 7, "XXX", "1111"), day).customerToday(),
                "each currency has its own total");
        assertEquals(Optional.of(Amount.of(100, "XTS")),
                store.recordAttempt(payment(T0.plus(Duration.ofDays(1)), 100, "XTS", "1111"), day.plusDays(1))
                        .customerToday(), "a new day starts from zero");
        assertEquals(Optional.empty(), store.recordAttempt(payment(T0.plusSeconds(180), 1, "XTS", "1111"))
                .customerToday(), "without the business day there is no total");

        store.prune(T0.plus(Duration.ofDays(3)));
        assertEquals(Optional.of(Amount.of(100, "XTS")),
                store.recordAttempt(payment(T0.plusSeconds(240), 100, "XTS", "1111"), day).customerToday(),
                "old days are forgotten");
    }

    @Test
    void pastTheCapACustomersAttemptsAreStillAdded() {
        InMemoryVelocityStore store = new InMemoryVelocityStore(2);
        LocalDate day = LocalDate.of(2026, 10, 25);
        for (int i = 0; i < 3; i++) {
            store.recordAttempt(payment(T0.plusSeconds(i), 100, "XTS", "1111"), day);
        }
        assertEquals(Optional.of(Amount.of(400, "XTS")),
                store.recordAttempt(payment(T0.plusSeconds(3), 100, "XTS", "1111"), day).customerToday());
    }
}
