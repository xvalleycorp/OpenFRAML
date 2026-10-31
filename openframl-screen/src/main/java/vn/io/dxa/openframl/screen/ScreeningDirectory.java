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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import vn.io.dxa.openframl.pack.ListSource;

/**
 * The lists for name screening, kept in one directory and reloaded when a file changes:
 *
 * <ul>
 *   <li>{@code <source>@<version>.csv}: a list in the {@linkplain ListCsv open CSV form};</li>
 *   <li>{@code UN-SC*.xml}: the {@linkplain UnConsolidatedList UN Security Council Consolidated List}, whose
 *       version is read from the file.</li>
 * </ul>
 *
 * <p>Each load builds a new {@link Screener}; {@link #current} switches to it at once, so a screening sees one
 * version of every list. If a file cannot be read, the previous screener stays in use and the error is logged.
 */
public final class ScreeningDirectory implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ScreeningDirectory.class.getName());
    private static final Pattern CSV = Pattern.compile("([A-Za-z0-9_.-]+)@([A-Za-z0-9_.-]+)\\.csv");
    private static final Pattern UN = Pattern.compile("UN-SC[A-Za-z0-9_.@-]*\\.xml");

    private final Path directory;
    private final NameRules rules;
    private volatile Screener current;
    private String loaded;
    private ScheduledExecutorService scheduler;

    private ScreeningDirectory(Path directory, NameRules rules) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    /** Loads now, failing if a list cannot be read, then checks for changes every {@code interval}. */
    public static ScreeningDirectory start(Path directory, Duration interval) throws IOException {
        return start(directory, NameRules.BASE, interval);
    }

    /** Like {@link #start(Path, Duration)}, comparing names with a country's rules. */
    public static ScreeningDirectory start(Path directory, NameRules rules, Duration interval) throws IOException {
        ScreeningDirectory lists = open(directory, rules);
        lists.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("openframl-screening-lists").daemon().factory());
        lists.scheduler.scheduleWithFixedDelay(lists::reload, interval.toMillis(), interval.toMillis(),
                TimeUnit.MILLISECONDS);
        return lists;
    }

    /** Loads now, without reloading on its own; call {@link #load} to pick up changes. */
    public static ScreeningDirectory open(Path directory) throws IOException {
        return open(directory, NameRules.BASE);
    }

    /** Like {@link #open(Path)}, comparing names with a country's rules. */
    public static ScreeningDirectory open(Path directory, NameRules rules) throws IOException {
        ScreeningDirectory lists = new ScreeningDirectory(directory, rules);
        lists.load();
        return lists;
    }

    /** The lists among {@code expected} (e.g. a country pack's) that the directory does not hold. */
    public List<ListSource> missing(List<ListSource> expected) {
        Set<String> held = current().sources();
        return expected.stream().filter(source -> !held.contains(source.id())).toList();
    }

    /** The screener of the lists in force. */
    public Screener current() {
        return current;
    }

    /**
     * Loads the lists if any file changed since the last load.
     *
     * @return whether a new screener is in use
     */
    public synchronized boolean load() throws IOException {
        List<Path> files;
        try (Stream<Path> listing = Files.list(directory)) {
            files = listing.filter(file -> {
                String name = file.getFileName().toString();
                return CSV.matcher(name).matches() || UN.matcher(name).matches();
            }).sorted().toList();
        }
        StringBuilder state = new StringBuilder();
        for (Path file : files) {
            state.append(file.getFileName()).append(' ').append(Files.size(file)).append(' ')
                    .append(Files.getLastModifiedTime(file).toMillis()).append('\n');
        }
        if (state.toString().equals(loaded)) {
            return false;
        }
        List<ScreeningList> lists = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            Matcher csv = CSV.matcher(name);
            lists.add(csv.matches() ? ListCsv.read(file, csv.group(1), csv.group(2)) : UnConsolidatedList.read(file));
        }
        current = Screener.of(lists, rules);
        loaded = state.toString();
        LOG.log(INFO, "screening lists loaded from {0}: {1}", directory, lists.stream()
                .map(list -> list.source() + "@" + list.version() + " (" + list.entries().size() + ")").toList());
        return true;
    }

    private void reload() {
        try {
            load();
        } catch (IOException | RuntimeException e) {
            LOG.log(ERROR, "screening lists not reloaded from {0}, keeping the lists loaded before: {1}", directory,
                    e.getMessage());
        }
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
