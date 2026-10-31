//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.valkey;

import io.lettuce.core.ScoredValue;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import vn.io.dxa.openframl.model.Amount;
import vn.io.dxa.openframl.monitor.ActivityStore;
import vn.io.dxa.openframl.monitor.Movement;

/**
 * Transaction Monitoring's account activity on Valkey, so that every Stream Service process sees every account's
 * history, and a partition that moves to another process after a rebalance keeps it.
 *
 * <p>Each account has two keys with one hash tag, so a cluster keeps them on one node: its event ids scored by time,
 * and its movements by event id. Recording a movement is one atomic script that records it once and drops what is
 * older than the retention; both keys expire after the retention, so an account that stops moving leaves nothing
 * behind. Counterparties are stored as the hash the monitor already gives them, never as account numbers.
 */
public final class ValkeyActivityStore implements ActivityStore {

    private static final char SEPARATOR = '\u001f';
    private static final LuaScript ADD = LuaScript.load("activity-add.lua");
    private static final LuaScript SINCE = LuaScript.load("activity-since.lua");

    private final RedisAsyncCommands<String, String> commands;
    private final ValkeyLayout layout;
    private final Duration retention;

    /** Keeps 35 days, like the in-memory store: the 30-day windows and a margin. */
    public ValkeyActivityStore(Valkey valkey) {
        this(valkey.connection(), ValkeyLayout.DEFAULT, Duration.ofDays(35));
    }

    public ValkeyActivityStore(StatefulRedisConnection<String, String> connection, ValkeyLayout layout,
            Duration retention) {
        this.commands = connection.async();
        this.layout = Objects.requireNonNull(layout, "layout");
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        this.retention = retention;
    }

    @Override
    public Optional<Instant> lastActivity(String account) {
        List<ScoredValue<String>> last = LuaScript.await(commands.zrangeWithScores(layout.activityTimes(account),
                -1, -1));
        return last.isEmpty() ? Optional.empty()
                : Optional.of(Instant.ofEpochMilli((long) last.getFirst().getScore()));
    }

    @Override
    public boolean add(String account, Movement movement) {
        String[] keys = {layout.activityTimes(account), layout.activityMovements(account)};
        Long added = ADD.run(commands, ScriptOutputType.INTEGER, keys,
                Long.toString(movement.at().toEpochMilli()), movement.eventId(), encode(movement),
                Long.toString(retention.toMillis()));
        return added != null && added == 1;
    }

    @Override
    public boolean contains(String account, String eventId) {
        return Boolean.TRUE.equals(LuaScript.await(commands.hexists(layout.activityMovements(account), eventId)));
    }

    @Override
    public List<Movement> since(String account, Instant since) {
        String[] keys = {layout.activityTimes(account), layout.activityMovements(account)};
        List<Object> reply = SINCE.run(commands, ScriptOutputType.MULTI, keys, Long.toString(since.toEpochMilli()));
        List<Movement> movements = new ArrayList<>(reply.size());
        for (Object value : reply) {
            if (value != null) {
                movements.add(decode(value.toString()));
            }
        }
        return movements;
    }

    /** The movement as stored: event id, time, direction, amount, currency, cash, counterparty hash. */
    static String encode(Movement movement) {
        return String.join(String.valueOf(SEPARATOR), movement.eventId(),
                Long.toString(movement.at().toEpochMilli()), movement.direction().name(),
                movement.amount().value().toPlainString(), movement.amount().currency(),
                movement.cash() ? "1" : "0", movement.counterparty() == null ? "" : movement.counterparty());
    }

    static Movement decode(String value) {
        String[] parts = value.split(String.valueOf(SEPARATOR), -1);
        if (parts.length != 7) {
            throw new IllegalStateException("not a movement: " + parts.length + " fields");
        }
        return new Movement(parts[0], Instant.ofEpochMilli(Long.parseLong(parts[1])),
                Movement.Direction.valueOf(parts[2]), new Amount(new BigDecimal(parts[3]), parts[4]),
                parts[5].equals("1"), parts[6].isEmpty() ? null : parts[6]);
    }
}
