//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.screen;

import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import vn.io.dxa.openframl.gate.WatchlistHit;
import vn.io.dxa.openframl.gate.memory.InMemoryWatchlist;
import vn.io.dxa.openframl.model.AccountRef;

/**
 * Keeps a gate's watchlist equal to a directory of {@linkplain AccountLists account lists}: loads it at start and
 * again whenever a file is added, removed or changed. The watchlist is replaced as a whole, so a lookup sees either
 * the old lists or the new ones. If a list cannot be read, the watchlist keeps the lists it has and the error is
 * logged; a gate never loses its watchlist because of a bad file.
 *
 * <p>Write a new list under a temporary name and rename it into place, so it is never read half-written.
 */
public final class WatchlistDirectory implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(WatchlistDirectory.class.getName());

    private final Path directory;
    private final InMemoryWatchlist watchlist;
    private ScheduledExecutorService scheduler;
    private String loaded;

    public WatchlistDirectory(Path directory, InMemoryWatchlist watchlist) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.watchlist = Objects.requireNonNull(watchlist, "watchlist");
    }

    /**
     * Loads now, and fails if the lists cannot be read: a gate should not start without them. Then checks every
     * {@code interval}.
     */
    public static WatchlistDirectory start(Path directory, InMemoryWatchlist watchlist, Duration interval)
            throws IOException {
        WatchlistDirectory lists = new WatchlistDirectory(directory, watchlist);
        lists.load();
        lists.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("openframl-watchlist").daemon().factory());
        lists.scheduler.scheduleWithFixedDelay(lists::reload, interval.toMillis(), interval.toMillis(),
                TimeUnit.MILLISECONDS);
        return lists;
    }

    /**
     * Loads the lists if they changed since the last load.
     *
     * @return whether the watchlist was replaced
     */
    public synchronized boolean load() throws IOException {
        String state = state();
        if (state.equals(loaded)) {
            return false;
        }
        Map<AccountRef, WatchlistHit> accounts = AccountLists.readDirectory(directory);
        watchlist.replace(accounts);
        loaded = state;
        LOG.log(INFO, "watchlist loaded from {0}: {1} accounts", directory, accounts.size());
        return true;
    }

    private void reload() {
        try {
            load();
        } catch (IOException | RuntimeException e) {
            LOG.log(ERROR, "watchlist not reloaded from {0}, keeping the lists loaded before: {1}", directory,
                    e.getMessage());
        }
    }

    /** The names, sizes and modification times of the list files. */
    private String state() throws IOException {
        StringBuilder state = new StringBuilder();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".csv")).sorted().toList()) {
                FileTime modified = Files.getLastModifiedTime(file);
                state.append(file.getFileName()).append(' ').append(Files.size(file)).append(' ')
                        .append(modified.toMillis()).append('\n');
            }
        }
        return state.toString();
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
