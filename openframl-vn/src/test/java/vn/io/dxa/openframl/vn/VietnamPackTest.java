//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.vn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import vn.io.dxa.openframl.gate.AccountProfile;
import vn.io.dxa.openframl.gate.Gate;
import vn.io.dxa.openframl.gate.GateOutcome;
import vn.io.dxa.openframl.gate.WatchlistHit;
import vn.io.dxa.openframl.gate.memory.InMemoryProfileStore;
import vn.io.dxa.openframl.gate.memory.InMemoryVelocityStore;
import vn.io.dxa.openframl.gate.memory.InMemoryWatchlist;
import vn.io.dxa.openframl.model.AccountRef;
import vn.io.dxa.openframl.model.Amount;
import vn.io.dxa.openframl.model.Channel;
import vn.io.dxa.openframl.model.Decision;
import vn.io.dxa.openframl.model.SessionSignals;
import vn.io.dxa.openframl.model.TransactionEvent;
import vn.io.dxa.openframl.model.TransactionType;
import vn.io.dxa.openframl.pack.CountryPack;
import vn.io.dxa.openframl.pack.PackResource;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.RuleStone;
import vn.io.dxa.rulestone.test.RuleCoverage;
import vn.io.dxa.rulestone.test.StoneTests;

class VietnamPackTest {

    // Sample bank codes outside the 9704xx range of Vietnamese banks, as in VOBS
    private static final AccountRef CUSTOMER = new AccountRef("999901", "0123456789");
    private static final AccountRef MULE = new AccountRef("999902", "5555666677");

    private static final CountryPack PACK = CountryPack.forCountry("VN");
    private static final List<DecisionTable> TABLES = readTables(PACK);
    private static final RuleCoverage COVERAGE = RuleCoverage.of(TABLES);

    @TestFactory
    Stream<DynamicTest> decisionTables() {
        RuleStoneEngine engine = RuleStone.builder().decisions(TABLES).build();
        return StoneTests.dynamicTests(engine, Path.of("src/test/rules"), COVERAGE);
    }

    @AfterAll
    static void everyRuleIsTested() {
        COVERAGE.assertComplete();
    }

    @Test
    void isFoundOnTheClassPath() {
        assertEquals(VietnamPack.class, PACK.getClass());
        assertEquals(ZoneId.of("Asia/Ho_Chi_Minh"), PACK.businessZone());
        assertEquals("VND", PACK.currency());
    }

    @Test
    void everyReasonHasAnEnglishAndAVietnameseMessage() {
        Set<String> codes = new TreeSet<>();
        TABLES.forEach(table -> table.rules().forEach(rule -> rule.reasonCode().ifPresent(codes::add)));
        codes.add(Gate.FALLBACK_FAILED);
        for (String code : codes) {
            Optional<String> english = PACK.reasonMessage(code, Locale.ENGLISH);
            Optional<String> vietnamese = PACK.reasonMessage(code, Locale.forLanguageTag("vi"));
            assertTrue(english.isPresent(), "no English message for " + code);
            assertTrue(vietnamese.isPresent(), "no Vietnamese message for " + code);
            assertNotEquals(english, vietnamese, "Vietnamese message for " + code + " is not translated");
        }
        assertEquals(PACK.reasonMessage("RAPID_SUCCESSION", Locale.ENGLISH),
                PACK.reasonMessage("RAPID_SUCCESSION", Locale.JAPANESE));
    }

    @Test
    void theGateChallengesAnImpersonationScam() {
        InMemoryProfileStore profiles = new InMemoryProfileStore()
                .account(CUSTOMER, new AccountProfile(LocalDate.of(2018, 5, 2), "LOW"))
                .account(MULE, new AccountProfile(LocalDate.of(2026, 10, 20), null))
                .knownDevice("cust-1", "phone-1");
        InMemoryWatchlist watchlist = new InMemoryWatchlist();
        try (Gate gate = Gate.builder(PACK).profiles(profiles).velocity(new InMemoryVelocityStore())
                .watchlist(watchlist).build()) {
            // The customer is on a call with someone posing as the police, who asks for 80,000,000 VND to be
            // "verified" in an account opened five days ago.
            TransactionEvent scam = TransactionEvent.builder().eventId("evt-1")
                    .occurredAt(Instant.parse("2026-10-25T02:15:00Z")).channel(Channel.MOBILE)
                    .type(TransactionType.TRANSFER_OUT).amount(Amount.of(80_000_000, "VND")).customerId("cust-1")
                    .originator(CUSTOMER).beneficiary(MULE)
                    .session(new SessionSignals("phone-1", "192.0.2.10", false, true, "BIOMETRIC")).build();
            GateOutcome outcome = gate.decide(scam, Duration.ofMillis(30));

            assertEquals(Decision.CHALLENGE, outcome.decision());
            assertEquals(List.of("ACTIVE_CALL_NEW_BENEFICIARY", "YOUNG_BENEFICIARY_ACCOUNT",
                    "NEW_BENEFICIARY_LARGE_AMOUNT"), outcome.reasonCodes());
            assertEquals(Optional.of("Khách hàng đang nghe điện thoại khi chuyển tiền cho người thụ hưởng mới"),
                    PACK.reasonMessage(outcome.reasonCodes().getFirst(), Locale.forLanguageTag("vi")));

            watchlist.replace(Map.of(MULE, new WatchlistHit("SUSPICIOUS_ACCOUNTS", "2026-10-25.1")));
            assertEquals(Decision.HOLD, gate.decide(scam, Duration.ofMillis(30)).decision());
        }
    }

    private static List<DecisionTable> readTables(CountryPack pack) {
        return pack.decisionTables().stream().map(VietnamPackTest::read).toList();
    }

    private static DecisionTable read(PackResource resource) {
        try (InputStream in = resource.open()) {
            return DecisionTableJson.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
