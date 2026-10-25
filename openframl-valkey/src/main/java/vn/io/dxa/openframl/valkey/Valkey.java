//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.valkey;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.Objects;

/**
 * One connection to Valkey, shared by the gate's stores. A single connection is enough: commands from all threads
 * are pipelined on it, and it reconnects on its own.
 */
public final class Valkey implements AutoCloseable {

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;

    private Valkey(RedisClient client, StatefulRedisConnection<String, String> connection) {
        this.client = client;
        this.connection = connection;
    }

    /**
     * Connects to {@code uri}, e.g. {@code redis://valkey-1:6379} or {@code rediss://...} for TLS.
     *
     * @param commandTimeout how long a command may take before it fails; keep it within the gate's budget for a
     *                       store, a few milliseconds
     */
    public static Valkey connect(String uri, Duration commandTimeout) {
        Objects.requireNonNull(commandTimeout, "commandTimeout");
        RedisClient client = RedisClient.create(RedisURI.create(uri));
        client.setOptions(ClientOptions.builder()
                .timeoutOptions(TimeoutOptions.enabled(commandTimeout))
                // While disconnected, fail at once rather than queue: the gate falls back instead of waiting
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
        try {
            return new Valkey(client, client.connect());
        } catch (RuntimeException e) {
            client.shutdown();
            throw e;
        }
    }

    public StatefulRedisConnection<String, String> connection() {
        return connection;
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
