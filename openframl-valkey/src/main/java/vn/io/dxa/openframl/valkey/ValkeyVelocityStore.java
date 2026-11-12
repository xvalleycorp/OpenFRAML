//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.valkey;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
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
 *
 * <p>Each customer's total for the business day is a hash of its own, sent with the attempt so that both cost one
 * round trip: it holds the event ids already counted and a running total per currency, in units of 1/10,000, kept
 * for 48 hours. Amounts with more than four decimals cannot be counted, and the call fails.
 */
public final class ValkeyVelocityStore implements VelocityStore {

    static final Duration SHORT_WINDOW = Duration.ofMinutes(10);
    static final Duration LONG_WINDOW = Duration.ofHours(24);
    private static final char SEPARATOR = '\u001f';

    private static final LuaScript SCRIPT = LuaScript.load("record-attempt.lua");
    private static final LuaScript CUSTOMER_DAY = LuaScript.load("customer-day.lua");
    static final int CUSTOMER_DAY_SCALE = 4;
    static final Duration CUSTOMER_DAY_TTL = Duration.ofHours(48);

    private final RedisAsyncCommands<String, String> commands;
    private final ValkeyLayout layout;
    private final int maxAttemptsPerAccount;

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
    }

    @Override
    public VelocitySnapshot recordAttempt(TransactionEvent event) {
        long at = event.occurredAt().toEpochMilli();
        String[] keys = {layout.velocity(event.originator())};
        String[] args = {Long.toString(at), member(event), Long.toString(LONG_WINDOW.toMillis()),
                Integer.toString(maxAttemptsPerAccount)};
        List<Object> reply = SCRIPT.run(commands, ScriptOutputType.MULTI, keys, args);
        return snapshot(reply, at, event.amount());
    }

    @Override
    public VelocitySnapshot recordAttempt(TransactionEvent event, LocalDate businessDate) {
        long at = event.occurredAt().toEpochMilli();
        Supplier<List<Object>> attempts = SCRIPT.start(commands, ScriptOutputType.MULTI,
                new String[] {layout.velocity(event.originator())}, Long.toString(at), member(event),
                Long.toString(LONG_WINDOW.toMillis()), Integer.toString(maxAttemptsPerAccount));
        Supplier<String> total = CUSTOMER_DAY.start(commands, ScriptOutputType.VALUE,
                new String[] {layout.customerDay(event.customerId(), businessDate)}, event.eventId(),
                event.amount().currency(), units(event.amount()), Long.toString(CUSTOMER_DAY_TTL.toMillis()));
        VelocitySnapshot snapshot = snapshot(attempts.get(), at, event.amount());
        return snapshot.withCustomerToday(new Amount(fromUnits(total.get()), event.amount().currency()));
    }

    /** A total kept in units of 1/10,000, without trailing zeros. */
    static BigDecimal fromUnits(String units) {
        BigDecimal value = new BigDecimal(units).movePointLeft(CUSTOMER_DAY_SCALE).stripTrailingZeros();
        return value.scale() < 0 ? value.setScale(0) : value;
    }

    /** The amount in units of 1/10,000, as the customer's total keeps it. */
    static String units(Amount amount) {
        try {
            return amount.value().setScale(CUSTOMER_DAY_SCALE, RoundingMode.UNNECESSARY).unscaledValue().toString();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("an amount with more than " + CUSTOMER_DAY_SCALE
                    + " decimals cannot be added to the customer's total", e);
        }
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

    private static String hash(String value) {
        return HexFormat.of().formatHex(LuaScript.digest("SHA-256", value), 0, 16);
    }
}
