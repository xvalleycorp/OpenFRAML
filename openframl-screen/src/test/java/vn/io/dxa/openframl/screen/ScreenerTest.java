//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** All names and numbers here are invented. */
class ScreenerTest {

    private static final ListEntry DUC = new ListEntry("XA-0001", ListEntry.Kind.PERSON,
            List.of("Nguyễn Văn Đức", "Duc Van Nguyen", "Nguyen V. Duc"), Set.of(1975), Set.of("XA"),
            Set.of("XA-P-1234567"), "Invented sanctions programme");
    private static final ListEntry TRADING = new ListEntry("XA-0002", ListEntry.Kind.ORGANISATION,
            List.of("Công ty TNHH Thương mại Hoàng Long"), Set.of(), Set.of("XA"), Set.of("0101234567"), "");
    private static final ListEntry PHAM = new ListEntry("PEP-17", ListEntry.Kind.PERSON,
            List.of("Phạm Thị Hồng Nhung"), Set.of(1980), Set.of(), Set.of(), "Invented PEP");

    private final Screener screener = Screener.of(List.of(
            new ScreeningList("XA-SANCTIONS", "2026-10-30", List.of(DUC, TRADING)),
            new ScreeningList("BANK-PEP", "2026-10", List.of(PHAM))));

    private List<ScreeningMatch> screen(String name) {
        return screener.screen(Subject.named(name), 0.85);
    }

    @Test
    void namesCompareWithoutDiacriticsCaseOrPunctuation() {
        assertEquals("nguyen van duc", Names.normalize("  NGUYỄN  Văn-Đức "));
        assertEquals(List.of("cong", "ty", "tnhh"), Names.tokens("Công ty, TNHH."));
    }

    @Test
    void findsANameWrittenWithoutDiacriticsInAnyOrder() {
        for (String name : List.of("NGUYEN VAN DUC", "Duc Nguyen Van", "nguyễn văn đức", "Van Duc Nguyen")) {
            List<ScreeningMatch> matches = screen(name);
            assertEquals("XA-0001", matches.getFirst().entry().id(), name);
            assertEquals(1.0, matches.getFirst().score(), 1e-9, name);
            assertEquals("XA-SANCTIONS", matches.getFirst().source());
            assertEquals("2026-10-30", matches.getFirst().listVersion());
        }
    }

    @Test
    void findsMisspellingsAndShortenedNames() {
        assertEquals("XA-0001", screen("Nguyen Van Duk").getFirst().entry().id());
        assertEquals("XA-0001", screen("Nguen Van Duc").getFirst().entry().id());
        assertEquals("PEP-17", screen("Pham Thi Nhung").getFirst().entry().id());
        assertEquals("XA-0002", screen("CONG TY TNHH TM HOANG LONG").getFirst().entry().id());
    }

    @Test
    void doesNotMatchOtherPeople() {
        assertEquals(List.of(), screen("Tran Thi Mai"));
        assertEquals(List.of(), screen("Le Van Duc"), "shares two common parts only");
        assertEquals(List.of(), screen("Nguyen Van Dung"), "Dung is another given name, not a spelling of Duc");
        assertEquals(List.of(), screen(""));
    }

    @Test
    void anIdentifierMatchesWhateverTheName() {
        Subject subject = new Subject("Unknown Holder", Optional.empty(), Optional.empty(), Set.of("xa p 1234567"));

        ScreeningMatch match = screener.screen(subject, 0.85).getFirst();
        assertEquals("XA-0001", match.entry().id());
        assertEquals(1.0, match.score());
        assertTrue(match.reasons().getFirst().startsWith("IDENTIFIER_MATCHES"), match.reasons()::toString);
    }

    @Test
    void aDifferentBirthYearOrKindLowersTheScore() {
        ScreeningMatch older = screener.screen(new Subject("Nguyen Van Duc", Optional.of(ListEntry.Kind.PERSON),
                Optional.of(1990), Set.of()), 0.5).getFirst();
        assertEquals(0.8, older.score(), 1e-9);
        assertEquals(List.of("NAME 1.00", "BIRTH_YEAR_DIFFERS"), older.reasons());

        ScreeningMatch close = screener.screen(new Subject("Nguyen Van Duc", Optional.empty(), Optional.of(1976),
                Set.of()), 0.5).getFirst();
        assertEquals(List.of("NAME 1.00", "BIRTH_YEAR_MATCHES"), close.reasons());

        ScreeningMatch company = screener.screen(new Subject("Nguyen Van Duc",
                Optional.of(ListEntry.Kind.ORGANISATION), Optional.empty(), Set.of()), 0.5).getFirst();
        assertEquals(0.85, company.score(), 1e-9);
    }

    @Test
    void eachEntryAppearsOnceWithItsClosestName() {
        List<ScreeningMatch> matches = screen("Duc Van Nguyen");
        assertEquals(1, matches.stream().filter(m -> m.entry().id().equals("XA-0001")).count());
    }

    @Test
    void screensALargeListQuickly() {
        String[] family = {"nguyen", "tran", "le", "pham", "hoang", "huynh", "phan", "vu", "vo", "dang", "bui", "do"};
        String[] middle = {"van", "thi", "huu", "duc", "minh", "thanh", "ngoc", "quang", "xuan", "kim"};
        String[] given = {"an", "binh", "chi", "dung", "giang", "hai", "hanh", "hoa", "khanh", "lan", "linh", "long",
            "mai", "nam", "phong", "quan", "son", "tam", "thao", "trang", "tuan", "vy", "yen"};
        Random random = new Random(7);
        List<ListEntry> entries = new ArrayList<>();
        for (int i = 0; i < 50_000; i++) {
            entries.add(new ListEntry("E" + i, ListEntry.Kind.PERSON, List.of(family[random.nextInt(family.length)]
                    + " " + middle[random.nextInt(middle.length)] + " " + given[random.nextInt(given.length)]
                    + " " + i), Set.of(), Set.of(), Set.of(), ""));
        }
        Screener large = Screener.of(List.of(new ScreeningList("SYNTHETIC", "1", entries)));
        for (int i = 0; i < 200; i++) {
            large.screen(Subject.named("Nguyen Van An"), 0.85);
        }
        long start = System.nanoTime();
        int runs = 1_000;
        for (int i = 0; i < runs; i++) {
            large.screen(Subject.named("Tran Thi Lan " + i), 0.85);
        }
        Duration mean = Duration.ofNanos((System.nanoTime() - start) / runs);
        // A loose bound for slow CI runners: screening is for onboarding and monitoring, not the gate's path
        assertTrue(mean.compareTo(Duration.ofMillis(50)) < 0, mean::toString);
    }
}
