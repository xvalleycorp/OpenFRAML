//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.screen;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import vn.io.dxa.openframl.gate.WatchlistHit;
import vn.io.dxa.openframl.model.AccountRef;

/**
 * Lists of suspicious accounts, for the gate's watchlist: the regulator's list of accounts suspected of fraud, the
 * bank's own, those shared between banks. Each list is one CSV file named {@code <source>@<version>.csv}, e.g.
 * {@code REGULATOR@2026-10-30.1.csv}, UTF-8, with the header {@code institution,account}.
 *
 * <p>An account on several lists is reported with the first, in file name order.
 */
public final class AccountLists {

    private static final Pattern FILE = Pattern.compile("([A-Za-z0-9_.-]+)@([A-Za-z0-9_.-]+)\\.csv");

    private AccountLists() {}

    /** Every list in {@code directory}, merged. */
    public static Map<AccountRef, WatchlistHit> readDirectory(Path directory) throws IOException {
        List<Path> files;
        try (Stream<Path> listing = Files.list(directory)) {
            files = listing.filter(file -> FILE.matcher(file.getFileName().toString()).matches()).sorted().toList();
        }
        Map<AccountRef, WatchlistHit> merged = new LinkedHashMap<>();
        for (Path file : files) {
            Matcher name = FILE.matcher(file.getFileName().toString());
            name.matches();
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                read(in, new WatchlistHit(name.group(1), name.group(2))).forEach(merged::putIfAbsent);
            }
        }
        return merged;
    }

    /**
     * @throws IllegalArgumentException naming the first row that cannot be read
     */
    public static Map<AccountRef, WatchlistHit> read(Reader in, WatchlistHit list) throws IOException {
        List<List<String>> rows = Csv.read(in);
        if (rows.isEmpty() || rows.getFirst().size() < 2
                || !rows.getFirst().get(0).replace("﻿", "").strip().toLowerCase(Locale.ROOT).equals("institution")
                || !rows.getFirst().get(1).strip().toLowerCase(Locale.ROOT).equals("account")) {
            throw new IllegalArgumentException("list " + list.source() + " must start with the header "
                    + "institution,account");
        }
        Map<AccountRef, WatchlistHit> accounts = new LinkedHashMap<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            try {
                accounts.putIfAbsent(new AccountRef(row.get(0).strip(), row.get(1).strip()), list);
            } catch (RuntimeException e) {
                // The message names the row only: never an account number
                throw new IllegalArgumentException("list " + list.source() + ", row " + (r + 1) + " is not an "
                        + "institution and an account", e);
            }
        }
        return accounts;
    }
}
