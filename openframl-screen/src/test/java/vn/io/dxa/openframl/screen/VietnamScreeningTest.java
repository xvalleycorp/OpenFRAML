//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.io.dxa.openframl.pack.CountryPack;
import vn.io.dxa.openframl.pack.ListSource;

/** The Vietnam pack's name rules and lists. All names and numbers are invented. */
class VietnamScreeningTest {

    private static final CountryPack VN = CountryPack.forCountry("VN");
    private static final NameRules RULES = NameRules.of(VN);

    @Test
    void vietnameseBusinessAbbreviationsAreWrittenOut() {
        assertEquals(List.of("thuong", "mai", "dich", "vu", "xuat", "nhap", "khau", "hoang", "long"),
                RULES.core(RULES.tokens("Công ty TNHH MTV TM DV XNK Hoàng Long")));
        assertTrue(RULES.legalForms().containsAll(NameRules.BASE.legalForms()), "the international forms stay");

        ListEntry listed = new ListEntry("VN-0001", ListEntry.Kind.ORGANISATION,
                List.of("Công ty Cổ phần Xây dựng Đầu tư Hoàng Long"), Set.of(), Set.of(), Set.of(), "");
        Screener screener = Screener.of(List.of(new ScreeningList("VN-WARNING", "2026-10", List.of(listed))), RULES);
        ScreeningMatch match = screener.screen(Subject.named("CTY CP XD DT HOANG LONG"), 0.85).getFirst();
        assertEquals("VN-0001", match.entry().id());
        assertEquals(List.of(), Screener.of(List.of(new ScreeningList("VN-WARNING", "2026-10", List.of(listed))))
                .screen(Subject.named("CTY CP XD DT HOANG LONG"), 0.85), "without the pack's rules it is missed");
    }

    @Test
    void theDirectoryNamesTheListsThePackExpectsButLacks(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("VN-WARNING@2026-10.csv"), """
                id,kind,names,birth_year,nationalities,identifiers,remark
                VN-0001,ORGANISATION,Công ty Cổ phần Hoàng Long,,,,Invented
                """);
        ScreeningDirectory lists = ScreeningDirectory.open(dir, RULES);

        assertEquals(List.of("UN-SC", "VN-BLACKLIST-TERRORISM", "VN-BLACKLIST-PROLIFERATION"),
                lists.missing(VN.screeningLists()).stream().map(ListSource::id).toList());
    }
}
