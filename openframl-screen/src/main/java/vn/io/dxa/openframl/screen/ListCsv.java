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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Set;

/**
 * The open CSV form of a list, for the bank's own lists (its PEPs, customers it has exited) and for official lists
 * converted from their publisher's format. UTF-8, one entry per row, with this header:
 *
 * <pre>
 * id,kind,names,birth_year,nationalities,identifiers,remark
 * </pre>
 *
 * <p>{@code kind} is {@code PERSON}, {@code ORGANISATION}, {@code VESSEL} or {@code OTHER}. {@code names},
 * {@code birth_year}, {@code nationalities} and {@code identifiers} hold several values separated by {@code |}, the
 * primary name first.
 * Only {@code id}, {@code kind} and {@code names} are required; columns may come in any order.
 */
public final class ListCsv {

    static final List<String> COLUMNS = List.of("id", "kind", "names", "birth_year", "nationalities", "identifiers",
            "remark");

    private ListCsv() {}

    public static ScreeningList read(Path file, String source, String version) throws IOException {
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return read(in, source, version);
        }
    }

    /**
     * @throws IllegalArgumentException naming the row of the first entry that cannot be read
     */
    public static ScreeningList read(Reader in, String source, String version) throws IOException {
        List<List<String>> rows = Csv.read(in);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("list " + source + " is empty: no header");
        }
        Map<String, Integer> column = new HashMap<>();
        List<String> header = rows.getFirst();
        for (int i = 0; i < header.size(); i++) {
            column.put(header.get(i).strip().toLowerCase(Locale.ROOT).replace("﻿", ""), i);
        }
        for (String required : List.of("id", "kind", "names")) {
            if (!column.containsKey(required)) {
                throw new IllegalArgumentException("list " + source + " has no column " + required);
            }
        }
        List<ListEntry> entries = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            try {
                entries.add(new ListEntry(cell(row, column, "id"),
                        ListEntry.Kind.valueOf(cell(row, column, "kind").toUpperCase(Locale.ROOT)),
                        values(cell(row, column, "names")),
                        values(cell(row, column, "birth_year")).stream().map(Integer::valueOf)
                                .collect(Collectors.toSet()),
                        Set.copyOf(values(cell(row, column, "nationalities"))),
                        Set.copyOf(values(cell(row, column, "identifiers"))),
                        cell(row, column, "remark")));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("list " + source + ", row " + (r + 1) + ": " + e.getMessage(), e);
            }
        }
        return new ScreeningList(source, version, entries);
    }

    private static String cell(List<String> row, Map<String, Integer> column, String name) {
        Integer i = column.get(name);
        return i == null || i >= row.size() ? "" : row.get(i).strip();
    }

    private static List<String> values(String cell) {
        return Arrays.stream(cell.split("\\|")).map(String::strip).filter(value -> !value.isEmpty()).distinct()
                .toList();
    }
}
