//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate.memory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import vn.io.dxa.openframl.gate.VelocitySnapshot;
import vn.io.dxa.openframl.gate.VelocityStore;
import vn.io.dxa.openframl.model.Amount;
import vn.io.dxa.openframl.model.TransactionEvent;

/**
 * Velocity counters in this node's memory: the gate's own short-term counters, for the accounts routed to this
 * node.
 *
 * <p>Windows are measured back from each payment's {@code occurredAt}, so replaying events gives the same counters.
 * Each account keeps its attempts of the last 24 hours, up to a cap; past the cap the oldest are dropped, so the
 * counters of a very busy account saturate rather than grow without bound. Call {@link #prune(Instant)}
 * periodically to forget idle accounts.
 */
public final class InMemoryVelocityStore implements VelocityStore {

    static final Duration SHORT_WINDOW = Duration.ofMinutes(10);
    static final Duration LONG_WINDOW = Duration.ofHours(24);

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxAttemptsPerAccount;

    /** Keeps up to 10,000 attempts per account. */
    public InMemoryVelocityStore() {
        this(10_000);
    }

    public InMemoryVelocityStore(int maxAttemptsPerAccount) {
        if (maxAttemptsPerAccount < 1) {
            throw new IllegalArgumentException("maxAttemptsPerAccount must be positive");
        }
        this.maxAttemptsPerAccount = maxAttemptsPerAccount;
    }

    @Override
    public VelocitySnapshot recordAttempt(TransactionEvent event) {
        Window window = windows.computeIfAbsent(event.originator().key(), key -> new Window());
        String beneficiary = event.beneficiary() == null ? null : event.beneficiary().key();
        return window.add(event.occurredAt().toEpochMilli(), event.amount(), beneficiary, maxAttemptsPerAccount);
    }

    /** Forgets accounts with no attempt in the 24 hours before {@code now}. */
    public void prune(Instant now) {
        long cutoff = now.toEpochMilli() - LONG_WINDOW.toMillis();
        windows.values().removeIf(window -> window.idleSince(cutoff));
    }

    /** Number of accounts with counters. */
    public int accounts() {
        return windows.size();
    }

    private record Attempt(long atMillis, BigDecimal amount, String currency, String beneficiary) {}

    private static final class Window {

        private final ArrayDeque<Attempt> attempts = new ArrayDeque<>();
        private long latestMillis = Long.MIN_VALUE;

        synchronized VelocitySnapshot add(long atMillis, Amount amount, String beneficiary, int cap) {
            attempts.addLast(new Attempt(atMillis, amount.value(), amount.currency(), beneficiary));
            latestMillis = Math.max(latestMillis, atMillis);
            long evictBefore = latestMillis - LONG_WINDOW.toMillis();
            while (!attempts.isEmpty() && (attempts.size() > cap || attempts.peekFirst().atMillis() <= evictBefore)) {
                attempts.pollFirst();
            }

            long shortFrom = atMillis - SHORT_WINDOW.toMillis();
            long longFrom = atMillis - LONG_WINDOW.toMillis();
            long count10m = 0;
            long count24h = 0;
            BigDecimal total = BigDecimal.ZERO;
            Set<String> beneficiaries = new HashSet<>();
            for (Attempt attempt : attempts) {
                if (attempt.atMillis() <= longFrom) {
                    continue;
                }
                count24h++;
                if (attempt.atMillis() > shortFrom) {
                    count10m++;
                }
                if (attempt.currency().equals(amount.currency())) {
                    total = total.add(attempt.amount());
                }
                if (attempt.beneficiary() != null) {
                    beneficiaries.add(attempt.beneficiary());
                }
            }
            return new VelocitySnapshot(count10m, count24h, new Amount(total, amount.currency()),
                    beneficiaries.size());
        }

        synchronized boolean idleSince(long cutoffMillis) {
            return latestMillis <= cutoffMillis;
        }
    }
}
