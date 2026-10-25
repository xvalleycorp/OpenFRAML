//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.valkey;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import vn.io.dxa.openframl.gate.AccountProfile;
import vn.io.dxa.openframl.gate.ProfileSnapshot;
import vn.io.dxa.openframl.gate.ProfileStore;
import vn.io.dxa.openframl.model.AccountRef;
import vn.io.dxa.openframl.model.TransactionEvent;

/**
 * Profiles on Valkey, in the {@link ValkeyLayout}, with an in-process cache in front.
 *
 * <p>A lookup sends the commands for whatever the cache does not have all at once, so it costs one round trip, or
 * none. Account profiles are cached, present or absent, until the cache's time to live; a known beneficiary or
 * device is cached once seen, since the stream layer only adds to those sets. A change written to Valkey therefore
 * reaches the gate within the time to live.
 */
public final class ValkeyProfileStore implements ProfileStore {

    private static final List<String> FIELDS = List.of(ValkeyLayout.OPENED_ON, ValkeyLayout.RISK);

    private final RedisAsyncCommands<String, String> commands;
    private final ValkeyLayout layout;
    private final Cache<String, Optional<AccountProfile>> accounts;
    private final Cache<String, Boolean> known;

    /** Caches up to a million entries of each kind for 60 seconds. */
    public ValkeyProfileStore(Valkey valkey) {
        this(valkey.connection(), ValkeyLayout.DEFAULT, Duration.ofSeconds(60), 1_000_000);
    }

    /**
     * @param cacheTtl  how long a cached entry is used; zero turns the cache off
     * @param cacheSize the most entries of each kind kept
     */
    public ValkeyProfileStore(StatefulRedisConnection<String, String> connection, ValkeyLayout layout,
            Duration cacheTtl, long cacheSize) {
        this.commands = connection.async();
        this.layout = Objects.requireNonNull(layout, "layout");
        this.accounts = Caffeine.newBuilder().expireAfterWrite(cacheTtl).maximumSize(cacheSize).build();
        this.known = Caffeine.newBuilder().expireAfterWrite(cacheTtl).maximumSize(cacheSize).build();
    }

    @Override
    public ProfileSnapshot lookup(TransactionEvent event) {
        AccountRef beneficiary = event.beneficiary();
        String deviceId = event.session().deviceId();

        // Send every command the cache cannot answer before waiting for any of them
        Pending<Optional<AccountProfile>> originator = account(event.originator());
        Pending<Optional<AccountProfile>> beneficiaryProfile =
                beneficiary == null ? Pending.done(Optional.empty()) : account(beneficiary);
        Pending<Boolean> payee = beneficiary == null ? Pending.done(false)
                : member(layout.payees(event.originator()), beneficiary.key());
        Pending<Boolean> device = deviceId == null ? Pending.done(false)
                : member(layout.devices(event.customerId()), deviceId);

        return new ProfileSnapshot(originator.get(), beneficiaryProfile.get(), payee.get(), device.get());
    }

    private Pending<Optional<AccountProfile>> account(AccountRef account) {
        String key = layout.account(account);
        Optional<AccountProfile> cached = accounts.getIfPresent(key);
        if (cached != null) {
            return Pending.done(cached);
        }
        RedisFuture<List<KeyValue<String, String>>> reply = commands.hmget(key, FIELDS.toArray(String[]::new));
        return () -> {
            Optional<AccountProfile> profile = profile(LuaScript.await(reply));
            accounts.put(key, profile);
            return profile;
        };
    }

    private Pending<Boolean> member(String key, String value) {
        String cacheKey = key + '\u001f' + value;
        if (known.getIfPresent(cacheKey) != null) {
            return Pending.done(true);
        }
        RedisFuture<Boolean> reply = commands.sismember(key, value);
        return () -> {
            boolean member = LuaScript.await(reply);
            if (member) {
                known.put(cacheKey, true);
            }
            return member;
        };
    }

    private static Optional<AccountProfile> profile(List<KeyValue<String, String>> fields) {
        String openedOn = fields.get(0).getValueOrElse(null);
        if (openedOn == null) {
            return Optional.empty();
        }
        return Optional.of(new AccountProfile(LocalDate.parse(openedOn), fields.get(1).getValueOrElse(null)));
    }

    /** Forgets every cached entry, e.g. after a bulk reload of the profiles. */
    public void invalidateCache() {
        accounts.invalidateAll();
        known.invalidateAll();
    }

    @FunctionalInterface
    private interface Pending<T> {

        T get();

        static <T> Pending<T> done(T value) {
            return () -> value;
        }
    }
}
