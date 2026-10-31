//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.monitor.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import vn.io.dxa.openframl.monitor.ActivityStore;
import vn.io.dxa.openframl.monitor.Movement;

/**
 * Activity in memory, for tests, trials and a single node. Keeps each account's movements for a retention period
 * (the longest scenario window), and its last activity for ever.
 */
public final class InMemoryActivityStore implements ActivityStore {

    private final Duration retention;
    private final ConcurrentHashMap<String, Account> accounts = new ConcurrentHashMap<>();

    /** Keeps 35 days of movements. */
    public InMemoryActivityStore() {
        this(Duration.ofDays(35));
    }

    public InMemoryActivityStore(Duration retention) {
        this.retention = retention;
    }

    @Override
    public Optional<Instant> lastActivity(String account) {
        Account found = accounts.get(account);
        return found == null ? Optional.empty() : found.last();
    }

    @Override
    public boolean add(String account, Movement movement) {
        return accounts.computeIfAbsent(account, key -> new Account()).add(movement, retention);
    }

    @Override
    public boolean contains(String account, String eventId) {
        Account found = accounts.get(account);
        return found != null && found.contains(eventId);
    }

    @Override
    public List<Movement> since(String account, Instant since) {
        Account found = accounts.get(account);
        return found == null ? List.of() : found.since(since);
    }

    private static final class Account {

        private final Deque<Movement> movements = new ArrayDeque<>();
        private final Set<String> events = new HashSet<>();
        private Instant last;

        synchronized boolean add(Movement movement, Duration retention) {
            if (!events.add(movement.eventId())) {
                return false;
            }
            movements.addLast(movement);
            last = last == null || movement.at().isAfter(last) ? movement.at() : last;
            Instant cutoff = last.minus(retention);
            while (!movements.isEmpty() && movements.peekFirst().at().isBefore(cutoff)) {
                events.remove(movements.pollFirst().eventId());
            }
            return true;
        }

        synchronized boolean contains(String eventId) {
            return events.contains(eventId);
        }

        synchronized Optional<Instant> last() {
            return Optional.ofNullable(last);
        }

        synchronized List<Movement> since(Instant since) {
            return movements.stream().filter(m -> !m.at().isBefore(since)).toList();
        }
    }
}
