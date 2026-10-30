//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Reads an invented sample in the structure of the UN list. */
class UnConsolidatedListTest {

    private static ScreeningList sample(boolean lowQualityAliases) throws IOException {
        try (InputStream in = UnConsolidatedListTest.class.getResourceAsStream("un-consolidated-sample.xml")) {
            return UnConsolidatedList.read(in, lowQualityAliases);
        }
    }

    @Test
    void readsIndividualsAndEntities() throws Exception {
        ScreeningList list = sample(false);
        assertEquals("UN-SC", list.source());
        assertEquals("2026-10-29T23:00:03.345Z", list.version());
        assertEquals(3, list.entries().size());

        ListEntry khoa = list.entries().getFirst();
        assertEquals("XAi.001", khoa.id());
        assertEquals(ListEntry.Kind.PERSON, khoa.kind());
        assertEquals(List.of("TRAN VAN KHOA", "Khoa Tran", "Тран Ван Хоа"), khoa.names());
        assertEquals(Set.of(1970, 1972, 1973, 1974), khoa.birthYears());
        assertEquals(Set.of("Testland", "Otherland"), khoa.nationalities());
        assertEquals(Set.of("XP 0000001"), khoa.identifiers());
        assertEquals("XA list, listed on 2020-01-15", khoa.remark());

        ListEntry noReference = list.entries().get(1);
        assertEquals("DATAID-900002", noReference.id());
        assertEquals(Set.of(1961), noReference.birthYears());

        ListEntry shipping = list.entries().get(2);
        assertEquals(ListEntry.Kind.ORGANISATION, shipping.kind());
        assertEquals(List.of("TESTLAND SHIPPING COMPANY (TSC)", "BLUE RIVER TRADING", "TSC Ltd"), shipping.names());
    }

    @Test
    void lowQualityAliasesOnlyWhenAsked() throws Exception {
        assertTrue(sample(true).entries().getFirst().names().contains("Uncle K"));
    }

    @Test
    void screensAgainstTheList() throws Exception {
        Screener screener = Screener.of(List.of(sample(false)));

        ScreeningMatch match = screener.screen(new Subject("Trần Văn Khoa", Optional.of(ListEntry.Kind.PERSON),
                Optional.of(1973), Set.of()), 0.85).getFirst();
        assertEquals("XAi.001", match.entry().id());
        assertEquals(List.of("NAME 1.00", "BIRTH_YEAR_MATCHES"), match.reasons());
        assertEquals("XAe.001", screener.screen(Subject.named("Blue River Trading Co., Ltd"), 0.85).getFirst()
                .entry().id());
    }

    @Test
    void refusesExternalEntitiesAndOtherDocuments() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<CONSOLIDATED_LIST dateGenerated=\"1\"><INDIVIDUALS><INDIVIDUAL><FIRST_NAME>&e;</FIRST_NAME>"
                + "</INDIVIDUAL></INDIVIDUALS></CONSOLIDATED_LIST>";
        assertThrows(IOException.class, () -> UnConsolidatedList.read(
                new ByteArrayInputStream(xxe.getBytes(StandardCharsets.UTF_8)), false));
        assertThrows(IllegalArgumentException.class, () -> UnConsolidatedList.read(
                new ByteArrayInputStream("<OTHER/>".getBytes(StandardCharsets.UTF_8)), false));
    }
}
