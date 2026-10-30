//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.cases.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import vn.io.dxa.openframl.cases.Alert;
import vn.io.dxa.openframl.cases.CaseFile;
import vn.io.dxa.openframl.cases.CaseFile.Action;
import vn.io.dxa.openframl.cases.CaseFile.Conclusion;
import vn.io.dxa.openframl.cases.CaseFile.Outcome;
import vn.io.dxa.openframl.cases.CaseFile.Status;
import vn.io.dxa.openframl.cases.CaseStore;

/**
 * Cases in a relational database through JDBC, in plain tables an examiner or a report can query: cases, their
 * alerts and evidence, and the history. PostgreSQL is the reference database ({@link #createSchema}); the bank
 * supplies the {@link DataSource} and the driver.
 *
 * <p>A save runs in one transaction. The case row carries a version, the length of its history, and is updated only
 * if the version is still the one read; alerts, evidence and actions are only inserted. A subject has at most one case
 * that is not closed, enforced by a unique index, so two nodes raising alerts at once cannot open two cases.
 */
public final class JdbcCaseStore implements CaseStore {

    private static final String UNIQUE_VIOLATION = "23505";

    private final DataSource dataSource;

    public JdbcCaseStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Creates the tables if they do not exist, for trials and tests; production runs the bank's own migrations. */
    public static void createSchema(DataSource dataSource) {
        String sql;
        try (InputStream in = JdbcCaseStore.class.getResourceAsStream("schema-postgresql.sql")) {
            sql = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String part : sql.replaceAll("(?m)^--.*$", "").split(";")) {
                if (!part.isBlank()) {
                    statement.execute(part);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create the case tables", e);
        }
    }

    @Override
    public Optional<CaseFile> find(String caseId) {
        return query("SELECT * FROM ofr_case WHERE id = ?", caseId).stream().findFirst();
    }

    @Override
    public Optional<CaseFile> openCaseOf(String subject) {
        return query("SELECT * FROM ofr_case WHERE subject = ? AND status <> 'CLOSED'", subject).stream().findFirst();
    }

    @Override
    public Optional<CaseFile> caseOfAlert(String alertId) {
        return query("SELECT c.* FROM ofr_case c JOIN ofr_case_alert a ON a.case_id = c.id WHERE a.alert_id = ?",
                alertId).stream().findFirst();
    }

    @Override
    public List<CaseFile> withStatus(Status status) {
        return query("SELECT * FROM ofr_case WHERE status = ? ORDER BY opened_at, id", status.name());
    }

    @Override
    public void save(CaseFile previous, CaseFile next) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (previous == null) {
                    insertCase(connection, next);
                } else {
                    updateCase(connection, previous, next);
                }
                int alertsFrom = previous == null ? 0 : previous.alerts().size();
                for (int i = alertsFrom; i < next.alerts().size(); i++) {
                    insertAlert(connection, next.id(), i, next.alerts().get(i));
                }
                int actionsFrom = previous == null ? 0 : previous.history().size();
                for (int i = actionsFrom; i < next.history().size(); i++) {
                    insertAction(connection, next.id(), i, next.history().get(i));
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                throw new ConcurrentModificationException("case " + next.id() + " conflicts with a concurrent save",
                        e);
            }
            throw new IllegalStateException("cannot save case " + next.id(), e);
        }
    }

    private static void insertCase(Connection connection, CaseFile c) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO ofr_case (id, subject, status, "
                + "assignee, proposal_outcome, proposal_reason, proposal_by, conclusion_outcome, conclusion_reason, "
                + "conclusion_by, approved_by, opened_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, c.id());
            insert.setString(2, c.subject());
            bindState(insert, 3, c);
            insert.setTimestamp(12, Timestamp.from(c.openedAt()));
            insert.setInt(13, c.history().size());
            insert.executeUpdate();
        }
    }

    private static void updateCase(Connection connection, CaseFile previous, CaseFile next) throws SQLException {
        if (!previous.id().equals(next.id()) || next.history().size() <= previous.history().size()
                || next.alerts().size() < previous.alerts().size()) {
            throw new IllegalArgumentException("a save must extend the case's history");
        }
        try (PreparedStatement update = connection.prepareStatement("UPDATE ofr_case SET status = ?, assignee = ?, "
                + "proposal_outcome = ?, proposal_reason = ?, proposal_by = ?, conclusion_outcome = ?, "
                + "conclusion_reason = ?, conclusion_by = ?, approved_by = ?, version = ? WHERE id = ? AND version = ?")) {
            bindState(update, 1, next);
            update.setInt(10, next.history().size());
            update.setString(11, next.id());
            update.setInt(12, previous.history().size());
            if (update.executeUpdate() != 1) {
                throw new ConcurrentModificationException("case " + next.id() + " changed since it was read");
            }
        }
    }

    /** Status, assignee, proposal and conclusion: nine parameters from {@code first}. */
    private static void bindState(PreparedStatement statement, int first, CaseFile c) throws SQLException {
        statement.setString(first, c.status().name());
        text(statement, first + 1, c.assignee().orElse(null));
        text(statement, first + 2, c.proposal().map(p -> p.outcome().name()).orElse(null));
        text(statement, first + 3, c.proposal().map(Conclusion::reason).orElse(null));
        text(statement, first + 4, c.proposal().map(Conclusion::proposedBy).orElse(null));
        text(statement, first + 5, c.conclusion().map(p -> p.outcome().name()).orElse(null));
        text(statement, first + 6, c.conclusion().map(Conclusion::reason).orElse(null));
        text(statement, first + 7, c.conclusion().map(Conclusion::proposedBy).orElse(null));
        text(statement, first + 8, c.conclusion().flatMap(Conclusion::approvedBy).orElse(null));
    }

    private static void insertAlert(Connection connection, String caseId, int seq, Alert alert) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO ofr_case_alert (alert_id, case_id, "
                + "seq, source, type, subject, event_id, score, raised_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, alert.id());
            insert.setString(2, caseId);
            insert.setInt(3, seq);
            insert.setString(4, alert.source());
            insert.setString(5, alert.type());
            insert.setString(6, alert.subject());
            text(insert, 7, alert.eventId().orElse(null));
            if (alert.score().isPresent()) {
                insert.setDouble(8, alert.score().get());
            } else {
                insert.setNull(8, Types.DOUBLE);
            }
            insert.setTimestamp(9, Timestamp.from(alert.raisedAt()));
            insert.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO ofr_case_alert_evidence (alert_id, seq, name, value) VALUES (?, ?, ?, ?)")) {
            int i = 0;
            for (Map.Entry<String, String> item : alert.evidence().entrySet()) {
                insert.setString(1, alert.id());
                insert.setInt(2, i++);
                insert.setString(3, item.getKey());
                insert.setString(4, item.getValue());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void insertAction(Connection connection, String caseId, int seq, Action action)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO ofr_case_action (case_id, seq, at, actor, what, detail) VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, caseId);
            insert.setInt(2, seq);
            insert.setTimestamp(3, Timestamp.from(action.at()));
            insert.setString(4, action.actor());
            insert.setString(5, action.what());
            insert.setString(6, action.detail());
            insert.executeUpdate();
        }
    }

    private List<CaseFile> query(String sql, String parameter) {
        try (Connection connection = dataSource.getConnection()) {
            // One snapshot for the case and its rows
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            List<CaseFile> cases = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(sql)) {
                select.setString(1, parameter);
                try (ResultSet row = select.executeQuery()) {
                    while (row.next()) {
                        cases.add(read(connection, row));
                    }
                }
            }
            connection.commit();
            return cases;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read cases", e);
        }
    }

    private static CaseFile read(Connection connection, ResultSet row) throws SQLException {
        String id = row.getString("id");
        Optional<Conclusion> proposal = Optional.ofNullable(row.getString("proposal_outcome")).map(outcome ->
                new Conclusion(Outcome.valueOf(outcome), string(row, "proposal_reason"), string(row, "proposal_by"),
                        Optional.empty()));
        Optional<Conclusion> conclusion = Optional.ofNullable(row.getString("conclusion_outcome")).map(outcome ->
                new Conclusion(Outcome.valueOf(outcome), string(row, "conclusion_reason"),
                        string(row, "conclusion_by"), Optional.ofNullable(string(row, "approved_by"))));
        return new CaseFile(id, row.getString("subject"), alerts(connection, id),
                Status.valueOf(row.getString("status")), Optional.ofNullable(row.getString("assignee")), proposal,
                conclusion, actions(connection, id), row.getTimestamp("opened_at").toInstant());
    }

    private static List<Alert> alerts(Connection connection, String caseId) throws SQLException {
        List<Alert> alerts = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT * FROM ofr_case_alert WHERE case_id = ? ORDER BY seq")) {
            select.setString(1, caseId);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    String alertId = row.getString("alert_id");
                    double score = row.getDouble("score");
                    Optional<Double> scored = row.wasNull() ? Optional.empty() : Optional.of(score);
                    alerts.add(new Alert(alertId, row.getString("source"), row.getString("type"),
                            row.getString("subject"), Optional.ofNullable(row.getString("event_id")), scored,
                            evidence(connection, alertId), row.getTimestamp("raised_at").toInstant()));
                }
            }
        }
        return alerts;
    }

    private static Map<String, String> evidence(Connection connection, String alertId) throws SQLException {
        Map<String, String> evidence = new LinkedHashMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT name, value FROM ofr_case_alert_evidence WHERE alert_id = ? ORDER BY seq")) {
            select.setString(1, alertId);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    evidence.put(row.getString("name"), row.getString("value"));
                }
            }
        }
        return evidence;
    }

    private static List<Action> actions(Connection connection, String caseId) throws SQLException {
        List<Action> actions = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT at, actor, what, detail FROM ofr_case_action WHERE case_id = ? ORDER BY seq")) {
            select.setString(1, caseId);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    Instant at = row.getTimestamp("at").toInstant();
                    actions.add(new Action(at, row.getString("actor"), row.getString("what"),
                            row.getString("detail")));
                }
            }
        }
        return actions;
    }

    private static void text(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    /** {@link ResultSet#getString}, for use inside lambdas. */
    private static String string(ResultSet row, String column) {
        try {
            return row.getString(column);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
