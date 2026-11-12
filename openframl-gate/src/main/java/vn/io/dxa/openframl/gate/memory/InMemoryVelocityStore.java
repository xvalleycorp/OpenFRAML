//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate.memory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
 *
 * <p>It also keeps each customer's total for the business day, on every account, counting an event id once; past the
 * cap on attempts, further ones are still added but no longer checked for repeats. On several nodes the total covers only the payments this node decided; a customer whose accounts are routed to
 * different nodes needs the Valkey store.
 */
public final class InMemoryVelocityStore implements VelocityStore {

    static final Duration SHORT_WINDOW = Duration.ofMinutes(10);
    static final Duration LONG_WINDOW = Duration.ofHours(24);

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CustomerDay> customerDays = new ConcurrentHashMap<>();
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

    @Override
    public VelocitySnapshot recordAttempt(TransactionEvent event, LocalDate businessDate) {
        VelocitySnapshot snapshot = recordAttempt(event);
        CustomerDay day = customerDays.computeIfAbsent(event.customerId() + '|' + businessDate,
                key -> new CustomerDay(businessDate));
        return snapshot.withCustomerToday(day.add(event.eventId(), event.amount(), maxAttemptsPerAccount));
    }

    /**
     * Forgets accounts with no attempt in the 24 hours before {@code now}, and customers' totals of the days before
     * yesterday.
     */
    public void prune(Instant now) {
        long cutoff = now.toEpochMilli() - LONG_WINDOW.toMillis();
        windows.values().removeIf(window -> window.idleSince(cutoff));
        LocalDate yesterday = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(1);
        customerDays.values().removeIf(day -> day.date.isBefore(yesterday));
    }

    /** Number of accounts with counters. */
    public int accounts() {
        return windows.size();
    }

    /** A customer's attempts of one business day: their event ids and the totals by currency. */
    private static final class CustomerDay {

        private final LocalDate date;
        private final Set<String> events = new HashSet<>();
        private final Map<String, BigDecimal> totals = new HashMap<>();

        CustomerDay(LocalDate date) {
            this.date = date;
        }

        synchronized Amount add(String eventId, Amount amount, int cap) {
            boolean repeat = events.contains(eventId);
            if (!repeat && events.size() < cap) {
                events.add(eventId);
            }
            if (!repeat) {
                totals.merge(amount.currency(), amount.value(), BigDecimal::add);
            }
            return new Amount(totals.get(amount.currency()), amount.currency());
        }
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
