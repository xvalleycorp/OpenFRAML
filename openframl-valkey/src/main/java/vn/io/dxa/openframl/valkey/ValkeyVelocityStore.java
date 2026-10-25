//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.valkey;

import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import vn.io.dxa.openframl.gate.VelocitySnapshot;
import vn.io.dxa.openframl.gate.VelocityStore;
import vn.io.dxa.openframl.model.AccountRef;
import vn.io.dxa.openframl.model.Amount;
import vn.io.dxa.openframl.model.TransactionEvent;

/**
 * Velocity counters on Valkey, so they survive a restart and any node can serve any account.
 *
 * <p>Each attempt is one call: a short script adds it to the account's sorted set, trims the set to 24 hours and to
 * a cap, and returns what is left; the counts and the total are computed here, in exact decimal arithmetic. The
 * script runs atomically, so attempts on the same account from different nodes see each other. The attempt's
 * member is made from its event id, so a retried call is counted once.
 *
 * <p>Beneficiaries are stored as a truncated SHA-256 of their account, since the counters only need to tell them
 * apart. This keeps account numbers out of plain sight; it is not anonymisation, as account numbers are short enough
 * to guess. Access to Valkey must be restricted like access to the core banking data.
 */
public final class ValkeyVelocityStore implements VelocityStore {

    static final Duration SHORT_WINDOW = Duration.ofMinutes(10);
    static final Duration LONG_WINDOW = Duration.ofHours(24);
    private static final char SEPARATOR = '\u001f';

    private static final String SCRIPT = load("record-attempt.lua");

    private final RedisAsyncCommands<String, String> commands;
    private final ValkeyLayout layout;
    private final int maxAttemptsPerAccount;
    private final String sha;

    /** Keeps up to 10,000 attempts per account, like the in-memory store. */
    public ValkeyVelocityStore(Valkey valkey) {
        this(valkey.connection(), ValkeyLayout.DEFAULT, 10_000);
    }

    public ValkeyVelocityStore(StatefulRedisConnection<String, String> connection, ValkeyLayout layout,
            int maxAttemptsPerAccount) {
        this.commands = connection.async();
        this.layout = Objects.requireNonNull(layout, "layout");
        if (maxAttemptsPerAccount < 1) {
            throw new IllegalArgumentException("maxAttemptsPerAccount must be positive");
        }
        this.maxAttemptsPerAccount = maxAttemptsPerAccount;
        this.sha = sha1(SCRIPT);
    }

    @Override
    public VelocitySnapshot recordAttempt(TransactionEvent event) {
        long at = event.occurredAt().toEpochMilli();
        String[] keys = {layout.velocity(event.originator())};
        String[] args = {Long.toString(at), member(event), Long.toString(LONG_WINDOW.toMillis()),
                Integer.toString(maxAttemptsPerAccount)};
        List<Object> reply;
        try {
            reply = await(commands.<List<Object>>evalsha(sha, ScriptOutputType.MULTI, keys, args));
        } catch (RedisNoScriptException e) {
            // First call on this server, or after a restart: send the script itself, which also caches it
            reply = await(commands.<List<Object>>eval(SCRIPT, ScriptOutputType.MULTI, keys, args));
        }
        return snapshot(reply, at, event.amount());
    }

    /** The attempt as stored: event id, amount, currency, and a hash of the beneficiary. */
    static String member(TransactionEvent event) {
        AccountRef beneficiary = event.beneficiary();
        return event.eventId() + SEPARATOR + event.amount().value().toPlainString() + SEPARATOR
                + event.amount().currency() + SEPARATOR + (beneficiary == null ? "" : hash(beneficiary.key()));
    }

    /**
     * Counts the attempts the script returned, as member and score pairs, the way the in-memory store does: only
     * amounts in the payment's currency add to the total.
     */
    static VelocitySnapshot snapshot(List<?> reply, long at, Amount amount) {
        long shortFrom = at - SHORT_WINDOW.toMillis();
        long count10m = 0;
        long count24h = 0;
        BigDecimal total = BigDecimal.ZERO;
        Set<String> beneficiaries = new HashSet<>();
        for (int i = 0; i + 1 < reply.size(); i += 2) {
            String[] parts = text(reply.get(i)).split(String.valueOf(SEPARATOR), -1);
            long attemptAt = (long) Double.parseDouble(text(reply.get(i + 1)));
            count24h++;
            if (attemptAt > shortFrom) {
                count10m++;
            }
            if (parts[2].equals(amount.currency())) {
                total = total.add(new BigDecimal(parts[1]));
            }
            if (!parts[3].isEmpty()) {
                beneficiaries.add(parts[3]);
            }
        }
        return new VelocitySnapshot(count10m, count24h, new Amount(total, amount.currency()), beneficiaries.size());
    }

    private static String text(Object value) {
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString();
    }

    static <T> T await(io.lettuce.core.RedisFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for Valkey", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Valkey command failed", e.getCause());
        }
    }

    private static String hash(String value) {
        return HexFormat.of().formatHex(digest("SHA-256", value), 0, 16);
    }

    private static String sha1(String value) {
        return HexFormat.of().formatHex(digest("SHA-1", value));
    }

    private static byte[] digest(String algorithm, String value) {
        try {
            return MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String load(String name) {
        try (InputStream in = ValkeyVelocityStore.class.getResourceAsStream(name)) {
            return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
