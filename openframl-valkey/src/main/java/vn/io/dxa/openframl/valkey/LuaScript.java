//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.valkey;

import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

/** A script shipped with this module, sent by its hash and, the first time a server sees it, in full. */
final class LuaScript {

    private final String text;
    private final String sha;

    private LuaScript(String text) {
        this.text = text;
        this.sha = HexFormat.of().formatHex(digest("SHA-1", text));
    }

    static LuaScript load(String name) {
        try (InputStream in = LuaScript.class.getResourceAsStream(name)) {
            return new LuaScript(new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    <T> T run(RedisAsyncCommands<String, String> commands, ScriptOutputType type, String[] keys, String... args) {
        try {
            return await(commands.<T>evalsha(sha, type, keys, args));
        } catch (RedisNoScriptException e) {
            // First call on this server, or after a restart: send the script itself, which also caches it
            return await(commands.<T>eval(text, type, keys, args));
        }
    }

    static <T> T await(RedisFuture<T> future) {
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

    static byte[] digest(String algorithm, String value) {
        try {
            return MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
