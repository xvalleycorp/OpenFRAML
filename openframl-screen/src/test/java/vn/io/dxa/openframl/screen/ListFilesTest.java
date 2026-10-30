//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.io.dxa.openframl.gate.WatchlistHit;
import vn.io.dxa.openframl.gate.memory.InMemoryWatchlist;
import vn.io.dxa.openframl.model.AccountRef;

class ListFilesTest {

    private static final AccountRef MULE = new AccountRef("999902", "5555666677");
    private static final AccountRef OTHER = new AccountRef("999901", "1111222233");

    @TempDir
    Path directory;

    @Test
    void readsTheOpenCsvForm() throws Exception {
        String csv = "﻿id,kind,names,birth_year,nationalities,identifiers,remark\n"
                + "XA-1,person,\"Nguyễn Văn Đức|Duc, Nguyen Van\",1975,XA|XB,XA-P-1,\"said \"\"x\"\"\"\r\n"
                + "XA-2,ORGANISATION,Hoàng Long Co,,,,\n\n";
        ScreeningList list = ListCsv.read(new StringReader(csv), "XA-SANCTIONS", "2026-10-30");

        assertEquals(2, list.entries().size());
        ListEntry first = list.entries().getFirst();
        assertEquals(List.of("Nguyễn Văn Đức", "Duc, Nguyen Van"), first.names());
        assertEquals(Set.of(1975), first.birthYears());
        assertEquals(Set.of("XA", "XB"), first.nationalities());
        assertEquals("said \"x\"", first.remark());
        assertEquals(Set.of(), list.entries().get(1).birthYears());
    }

    @Test
    void namesTheRowThatCannotBeRead() {
        String csv = "id,kind,names\nXA-1,PERSON,An\nXA-2,ROBOT,Binh\n";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ListCsv.read(new StringReader(csv), "L", "1"));
        assertTrue(e.getMessage().contains("row 3"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ListCsv.read(new StringReader("id,names\n"), "L", "1"));
    }

    @Test
    void mergesAccountListsAndNamesEachBySourceAndVersion() throws Exception {
        Files.writeString(directory.resolve("BANK@2026-10-29.csv"), "institution,account\n999902,5555666677\n");
        Files.writeString(directory.resolve("REGULATOR@2026-10-30.1.csv"),
                "institution,account\n999902,5555666677\n999901,1111222233\n");
        Files.writeString(directory.resolve("notes.txt"), "ignored");

        Map<AccountRef, WatchlistHit> accounts = AccountLists.readDirectory(directory);

        assertEquals(new WatchlistHit("BANK", "2026-10-29"), accounts.get(MULE));
        assertEquals(new WatchlistHit("REGULATOR", "2026-10-30.1"), accounts.get(OTHER));
    }

    @Test
    void aBadRowIsReportedWithoutTheAccountNumber() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AccountLists.read(
                new StringReader("institution,account\n999902,\n"), new WatchlistHit("BANK", "1")));
        assertTrue(e.getMessage().contains("row 2"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> AccountLists.read(
                new StringReader("bank,acct\n"), new WatchlistHit("BANK", "1")));
    }

    @Test
    void theGatesWatchlistFollowsTheDirectoryAndSurvivesABadFile() throws Exception {
        Files.writeString(directory.resolve("REGULATOR@1.csv"), "institution,account\n999902,5555666677\n");
        InMemoryWatchlist watchlist = new InMemoryWatchlist();
        WatchlistDirectory lists = new WatchlistDirectory(directory, watchlist);

        assertTrue(lists.load());
        assertEquals(Optional.of(new WatchlistHit("REGULATOR", "1")), watchlist.find(MULE));
        assertFalse(lists.load(), "unchanged");

        Path next = directory.resolve("REGULATOR@2.csv.tmp");
        Files.writeString(next, "institution,account\n999901,1111222233\n");
        Files.move(next, directory.resolve("REGULATOR@2.csv"), StandardCopyOption.ATOMIC_MOVE);
        Files.delete(directory.resolve("REGULATOR@1.csv"));
        assertTrue(lists.load());
        assertEquals(Optional.empty(), watchlist.find(MULE));
        assertEquals(Optional.of(new WatchlistHit("REGULATOR", "2")), watchlist.find(OTHER));

        Files.writeString(directory.resolve("BANK@1.csv"), "not a list\n");
        assertThrows(IllegalArgumentException.class, lists::load);
        assertEquals(Optional.of(new WatchlistHit("REGULATOR", "2")), watchlist.find(OTHER), "kept");
    }
}
