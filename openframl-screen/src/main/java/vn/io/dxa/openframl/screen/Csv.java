//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.screen;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/** RFC 4180 CSV: comma-separated, double quotes around fields holding commas, quotes or line breaks. */
final class Csv {

    private Csv() {}

    /** Every non-blank row, header included. */
    static List<List<String>> read(Reader in) throws IOException {
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[8192];
        for (int n; (n = in.read(buffer)) != -1; ) {
            text.append(buffer, 0, n);
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c != '"') {
                    field.append(c);
                } else if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = false;
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else if (c != '\r') {
                field.append(c);
            }
        }
        if (quoted) {
            throw new IOException("unterminated quoted field");
        }
        row.add(field.toString());
        rows.add(row);
        rows.removeIf(r -> r.stream().allMatch(String::isBlank));
        return rows;
    }
}
