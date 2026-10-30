//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.cases.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import vn.io.dxa.openframl.cases.Alert;
import vn.io.dxa.openframl.cases.CaseFile;
import vn.io.dxa.openframl.cases.CaseFile.Outcome;
import vn.io.dxa.openframl.cases.CaseFile.Status;
import vn.io.dxa.openframl.cases.CaseService;
import vn.io.dxa.openframl.cases.CaseService.Investigator;
import vn.io.dxa.openframl.cases.CaseService.Role;

/**
 * Runs against a real PostgreSQL, e.g. {@code OPENFRAML_TEST_POSTGRES=jdbc:postgresql://localhost/openframl?user=..};
 * CI starts one. Each test uses its own subjects.
 */
@EnabledIfEnvironmentVariable(named = "OPENFRAML_TEST_POSTGRES", matches = ".+")
class JdbcCaseStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-30T03:00:00Z");
    private static final Investigator LAN = new Investigator("lan.analyst", Set.of(Role.ANALYST));
    private static final Investigator HA = new Investigator("ha.supervisor", Set.of(Role.SUPERVISOR));
    private static final DataSource DATABASE = new DriverDataSource(System.getenv("OPENFRAML_TEST_POSTGRES"));

    private final JdbcCaseStore store = new JdbcCaseStore(DATABASE);
    private final CaseService cases = new CaseService(store, Clock.fixed(NOW, ZoneOffset.UTC));
    private final String subject = "cust-" + UUID.randomUUID();

    @BeforeAll
    static void schema() {
        JdbcCaseStore.createSchema(DATABASE);
        JdbcCaseStore.createSchema(DATABASE);
    }

    private Alert alert(String id) {
        return new Alert(id + "-" + subject, "screening", "BENEFICIARY_NAME_MATCH", subject, Optional.of("evt-" + id),
                Optional.of(0.91), Map.of("list", "UN-SC", "entry", "XAi.001", "listedName", "TRAN VAN KHOA"), NOW);
    }

    @Test
    void aCaseReadBackIsTheCaseSaved() {
        CaseFile opened = cases.raise(alert("a1"));
        cases.raise(alert("a2"));
        cases.raise(new Alert("a3-" + subject, "gate", "HELD", subject, Optional.empty(), Optional.empty(), Map.of(),
                NOW));
        cases.assign(LAN, opened.id(), LAN);
        cases.note(LAN, opened.id(), "Checked the birth year: 1990 against 1975.");
        cases.propose(LAN, opened.id(), Outcome.FALSE_POSITIVE, "A namesake.");
        CaseFile closed = cases.approve(HA, opened.id());

        assertEquals(Optional.of(closed), store.find(opened.id()));
        assertEquals(Optional.of(closed), store.caseOfAlert("a2-" + subject));
        assertEquals(Optional.empty(), store.openCaseOf(subject));
        assertEquals(Map.of("list", "UN-SC", "entry", "XAi.001", "listedName", "TRAN VAN KHOA"),
                closed.alerts().getFirst().evidence());
        assertTrue(store.withStatus(Status.CLOSED).contains(closed));
    }

    @Test
    void aStaleSaveIsRefusedAndChangesNothing() {
        CaseFile opened = cases.raise(alert("a1"));
        CaseFile assigned = cases.assign(LAN, opened.id(), LAN);

        // Another node read the case before the assignment and saves on top of what it read
        assertThrows(ConcurrentModificationException.class, () -> store.save(opened, assigned));
        assertEquals(assigned, store.find(opened.id()).orElseThrow());
    }

    @Test
    void alertsRaisedAtOnceFromManyNodesOpenOneCase() throws Exception {
        List<Callable<CaseFile>> raises = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Alert alert = alert("c" + i);
            CaseService node = new CaseService(new JdbcCaseStore(DATABASE), Clock.fixed(NOW, ZoneOffset.UTC));
            raises.add(() -> node.raise(alert));
            if (i % 4 == 0) {
                raises.add(() -> node.raise(alert));
            }
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (Future<CaseFile> result : pool.invokeAll(raises)) {
                result.get();
            }
        }
        CaseFile c = store.openCaseOf(subject).orElseThrow();
        assertEquals(40, c.alerts().size());
        assertEquals(1, store.withStatus(Status.OPEN).stream().filter(x -> x.subject().equals(subject)).count());
    }

    /** A {@link DataSource} over {@link DriverManager}, for tests. */
    private record DriverDataSource(String url) implements DataSource {

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public Connection getConnection(String user, String password) throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> type) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> type) {
            return false;
        }
    }
}
