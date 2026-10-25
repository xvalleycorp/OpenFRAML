//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vn.io.dxa.openframl.gate.Gate;
import vn.io.dxa.openframl.gate.GateOutcome;
import vn.io.dxa.openframl.gate.memory.InMemoryVelocityStore;
import vn.io.dxa.openframl.model.Decision;
import vn.io.dxa.openframl.model.TransactionEvent;
import vn.io.dxa.openframl.pack.CountryPack;

class SyntheticBankTest {

    @Test
    void theSameSeedGivesTheSamePayments() {
        SyntheticBank.Feed first = new SyntheticBank(1_000, 42).feed(0, 1, 7);
        SyntheticBank.Feed second = new SyntheticBank(1_000, 42).feed(0, 1, 7);
        for (int i = 0; i < 100; i++) {
            assertEquals(first.next(), second.next());
        }
    }

    @Test
    void slicesShareNoCustomer() {
        SyntheticBank bank = new SyntheticBank(1_000, 42);
        Set<String> slice0 = new HashSet<>();
        Set<String> slice1 = new HashSet<>();
        SyntheticBank.Feed feed0 = bank.feed(0, 2, 7);
        SyntheticBank.Feed feed1 = bank.feed(1, 2, 7);
        for (int i = 0; i < 2_000; i++) {
            slice0.add(feed0.next().customerId());
            slice1.add(feed1.next().customerId());
        }
        slice0.retainAll(slice1);
        assertEquals(Set.of(), slice0);
    }

    @Test
    void theMixExercisesEveryDecisionTheVietnamPackMakes() {
        SyntheticBank bank = new SyntheticBank(5_000, 42);
        Map<Decision, Integer> decisions = new EnumMap<>(Decision.class);
        Set<String> reasons = new HashSet<>();
        try (Gate gate = Gate.builder(CountryPack.forCountry("VN")).profiles(bank.profiles())
                .velocity(new InMemoryVelocityStore()).watchlist(bank.watchlist()).build()) {
            SyntheticBank.Feed feed = bank.feed(0, 1, 7);
            for (int i = 0; i < 10_000; i++) {
                TransactionEvent event = feed.next();
                GateOutcome outcome = gate.decide(event, Duration.ofSeconds(1));
                decisions.merge(outcome.decision(), 1, Integer::sum);
                reasons.addAll(outcome.reasonCodes());
            }
        }
        assertTrue(decisions.get(Decision.ALLOW) > 8_000, decisions::toString);
        assertTrue(decisions.get(Decision.CHALLENGE) > 0, decisions::toString);
        assertTrue(decisions.get(Decision.HOLD) > 0, decisions::toString);
        assertTrue(reasons.containsAll(Set.of("BENEFICIARY_ON_WATCHLIST", "REMOTE_ACCESS_DURING_PAYMENT",
                "ACTIVE_CALL_NEW_BENEFICIARY", "NEW_DEVICE_LARGE_AMOUNT", "YOUNG_BENEFICIARY_ACCOUNT",
                "NEW_BENEFICIARY_LARGE_AMOUNT")), reasons::toString);
    }

    @Test
    void percentilesAreNearestRank() {
        Samples samples = new Samples(1_000);
        for (int i = 1; i <= 100; i++) {
            samples.add(i);
        }
        long[] values = samples.percentiles(50, 99, 99.9);
        assertEquals(50, values[0]);
        assertEquals(99, values[1]);
        assertEquals(100, values[2]);
        assertEquals(100, values[3]);
    }
}
