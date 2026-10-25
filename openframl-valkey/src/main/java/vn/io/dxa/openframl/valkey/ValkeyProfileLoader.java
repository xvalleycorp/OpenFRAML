//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.valkey;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import vn.io.dxa.openframl.gate.AccountProfile;
import vn.io.dxa.openframl.model.AccountRef;

/**
 * Writes profiles in the {@link ValkeyLayout}: for a batch job that loads them from the bank's systems (a small bank
 * without a stream layer), and for tests. The stream layer writes the same layout.
 */
public final class ValkeyProfileLoader {

    private final RedisCommands<String, String> commands;
    private final ValkeyLayout layout;

    public ValkeyProfileLoader(Valkey valkey) {
        this(valkey.connection(), ValkeyLayout.DEFAULT);
    }

    public ValkeyProfileLoader(StatefulRedisConnection<String, String> connection, ValkeyLayout layout) {
        this.commands = connection.sync();
        this.layout = Objects.requireNonNull(layout, "layout");
    }

    public ValkeyProfileLoader account(AccountRef account, AccountProfile profile) {
        String key = layout.account(account);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(ValkeyLayout.OPENED_ON, profile.openedOn().toString());
        if (profile.riskRating() == null) {
            commands.hdel(key, ValkeyLayout.RISK);
        } else {
            fields.put(ValkeyLayout.RISK, profile.riskRating());
        }
        commands.hset(key, fields);
        return this;
    }

    /** Records that {@code originator} has paid {@code beneficiary} before. */
    public ValkeyProfileLoader knownBeneficiary(AccountRef originator, AccountRef beneficiary) {
        commands.sadd(layout.payees(originator), beneficiary.key());
        return this;
    }

    /** Records that the customer has used the device before. */
    public ValkeyProfileLoader knownDevice(String customerId, String deviceId) {
        commands.sadd(layout.devices(customerId), deviceId);
        return this;
    }
}
