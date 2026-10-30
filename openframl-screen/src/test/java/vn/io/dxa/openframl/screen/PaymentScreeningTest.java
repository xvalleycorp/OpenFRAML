//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.io.dxa.openframl.cases.Alert;
import vn.io.dxa.openframl.cases.CaseFile;
import vn.io.dxa.openframl.cases.CaseService;
import vn.io.dxa.openframl.cases.memory.InMemoryCaseStore;
import vn.io.dxa.openframl.model.AccountRef;
import vn.io.dxa.openframl.model.Amount;
import vn.io.dxa.openframl.model.Channel;
import vn.io.dxa.openframl.model.TransactionEvent;
import vn.io.dxa.openframl.model.TransactionType;

class PaymentScreeningTest {

    private static final Instant NOW = Instant.parse("2026-10-30T03:00:00Z");

    @TempDir
    Path directory;

    private static TransactionEvent payment(String id, String customer, String beneficiaryName) {
        return TransactionEvent.builder().eventId(id).occurredAt(NOW).channel(Channel.MOBILE)
                .type(TransactionType.TRANSFER_OUT).amount(Amount.of(5_000_000, "VND")).customerId(customer)
                .originator(new AccountRef("999901", "0123456789"))
                .beneficiary(new AccountRef("999902", "5555666677")).beneficiaryName(beneficiaryName).build();
    }

    private ScreeningDirectory lists() throws Exception {
        Files.writeString(directory.resolve("BANK-PEP@2026-10.csv"),
                "id,kind,names\nPEP-17,PERSON,Phạm Thị Hồng Nhung\n");
        try (InputStream un = getClass().getResourceAsStream("un-consolidated-sample.xml")) {
            Files.copy(un, directory.resolve("UN-SC.xml"));
        }
        return ScreeningDirectory.open(directory);
    }

    @Test
    void aPaymentToAListedNameOpensACaseAboutThePayingCustomer() throws Exception {
        CaseService cases = new CaseService(new InMemoryCaseStore(), Clock.fixed(NOW, ZoneOffset.UTC));
        ScreeningDirectory lists = lists();
        PaymentScreening screening = new PaymentScreening(lists::current, 0.85, cases::raise, 1_000);

        List<Alert> alerts = screening.screen(payment("evt-1", "cust-1", "TRAN VAN KHOA"));

        assertEquals(1, alerts.size());
        Alert alert = alerts.getFirst();
        assertEquals("evt-1:UN-SC:XAi.001", alert.id());
        assertEquals("cust-1", alert.subject());
        assertEquals(PaymentScreening.ALERT_TYPE, alert.type());
        assertEquals("2026-10-29T23:00:03.345Z", alert.evidence().get("listVersion"));
        assertEquals("TRAN VAN KHOA", alert.evidence().get("paymentName"));
        assertFalse(alert.evidence().toString().contains("5555666677"), "no account number in the evidence");

        screening.screen(payment("evt-1", "cust-1", "TRAN VAN KHOA"));
        CaseFile c = cases.withStatus(CaseFile.Status.OPEN).getFirst();
        assertEquals(1, c.alerts().size(), "screening the same payment again raises nothing new");
        assertEquals("cust-1", c.subject());
    }

    @Test
    void paymentsWithoutANameOrToOtherNamesRaiseNothing() throws Exception {
        List<Alert> raised = new ArrayList<>();
        PaymentScreening screening = new PaymentScreening(lists()::current, 0.85, raised::add, 1_000);

        assertEquals(List.of(), screening.screen(payment("evt-1", "cust-1", null)));
        assertEquals(List.of(), screening.screen(payment("evt-2", "cust-1", "Le Thi Mai")));
        assertEquals(1, screening.screen(payment("evt-3", "cust-2", "Pham Thi Nhung")).size());
        assertEquals(1, raised.size());
    }

    @Test
    void namesAreScreenedOnceUntilTheListsChange() throws Exception {
        AtomicInteger built = new AtomicInteger();
        Screener[] current = {Screener.of(List.of(new ScreeningList("L", "1",
                List.of(new ListEntry("E1", ListEntry.Kind.PERSON, List.of("Tran Van Khoa"), Set.of(), Set.of(),
                        Set.of(), ""))))) };
        PaymentScreening screening = new PaymentScreening(() -> {
            built.incrementAndGet();
            return current[0];
        }, 0.85, alert -> {}, 10);

        Alert first = screening.screen(payment("evt-1", "c", "Trần Văn Khoa")).getFirst();
        Alert second = screening.screen(payment("evt-2", "c", "TRAN VAN KHOA")).getFirst();
        assertEquals(first.evidence().get("entry"), second.evidence().get("entry"));

        current[0] = Screener.of(List.of(new ScreeningList("L", "2", List.of())));
        assertEquals(List.of(), screening.screen(payment("evt-3", "c", "Tran Van Khoa")), "new lists, no cache");
        assertTrue(built.get() >= 3);
    }

    @Test
    void theDirectoryReloadsOnChangeAndKeepsTheOldListsOnABadFile() throws Exception {
        ScreeningDirectory lists = lists();
        Screener before = lists.current();
        assertFalse(lists.load(), "unchanged");
        assertSame(before, lists.current());

        Files.writeString(directory.resolve("BANK-EXITED@1.csv"), "id,kind,names\nX-1,PERSON,Vo Thanh Son\n");
        assertTrue(lists.load());
        assertEquals("X-1", lists.current().screen(Subject.named("Vo Thanh Son"), 0.85).getFirst().entry().id());

        Files.writeString(directory.resolve("BANK-BAD@1.csv"), "no,header\n");
        Screener good = lists.current();
        assertThrows(IllegalArgumentException.class, lists::load);
        assertSame(good, lists.current());
    }
}
