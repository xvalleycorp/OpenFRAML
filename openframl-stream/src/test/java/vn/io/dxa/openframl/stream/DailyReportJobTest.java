//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DailyReportJobTest {

    @TempDir
    Path dir;

    @Test
    void reportFilesAreReplacedWholeAndReadableByTheOwnerOnly() throws IOException {
        Path file = dir.resolve("transaction-reports-2026-10-30.jsonl");

        DailyReportJob.write(file, List.of("{\"a\":1}", "{\"b\":2}"));
        DailyReportJob.write(file, List.of("{\"c\":3}"));

        assertEquals(List.of("{\"c\":3}"), Files.readAllLines(file));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals(List.of(file), Files.list(dir).toList(), "no temporary file is left");
    }

    @Test
    void ratesAndHolidaysAreReadFromPlainFiles() throws IOException {
        Path rates = Files.writeString(dir.resolve("rates.csv"), "# VND per unit\nUSD,26300\nEUR, 30500.5\n\n");
        Path holidays = Files.writeString(dir.resolve("holidays.txt"), "# Tet\n2027-02-08\n2027-02-09\n");

        assertEquals(Optional.of(new BigDecimal("30500.5")),
                DailyReportJob.rates(rates.toString()).rate("EUR", LocalDate.of(2026, 10, 30)));
        assertEquals(Optional.empty(), DailyReportJob.rates(rates.toString()).rate("JPY", LocalDate.of(2026, 10, 30)));
        assertEquals(Set.of(LocalDate.of(2027, 2, 8), LocalDate.of(2027, 2, 9)),
                DailyReportJob.holidays(holidays.toString()));
        Path broken = Files.writeString(dir.resolve("broken.csv"), "USD;26300\n");
        assertThrows(IllegalArgumentException.class, () -> DailyReportJob.rates(broken.toString()));
    }
}
