-- Case Management tables, PostgreSQL. Cases are updated in place with an optimistic version; alerts, their
-- evidence and the history are only ever inserted, so the audit trail cannot be rewritten through the store.

CREATE TABLE IF NOT EXISTS ofr_case (
    id                  VARCHAR(64)  PRIMARY KEY,
    subject             VARCHAR(256) NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    assignee            VARCHAR(256),
    proposal_outcome    VARCHAR(32),
    proposal_reason     TEXT,
    proposal_by         VARCHAR(256),
    conclusion_outcome  VARCHAR(32),
    conclusion_reason   TEXT,
    conclusion_by       VARCHAR(256),
    approved_by         VARCHAR(256),
    opened_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    -- The number of actions in the history: every change adds one
    version             INTEGER NOT NULL
);

-- At most one case per subject that is not closed
CREATE UNIQUE INDEX IF NOT EXISTS ofr_case_open_subject ON ofr_case (subject) WHERE status <> 'CLOSED';
CREATE INDEX IF NOT EXISTS ofr_case_status ON ofr_case (status, opened_at);

CREATE TABLE IF NOT EXISTS ofr_case_alert (
    alert_id   VARCHAR(256) PRIMARY KEY,
    case_id    VARCHAR(64)  NOT NULL REFERENCES ofr_case (id),
    seq        INTEGER      NOT NULL,
    source     VARCHAR(64)  NOT NULL,
    type       VARCHAR(128) NOT NULL,
    subject    VARCHAR(256) NOT NULL,
    event_id   VARCHAR(256),
    score      DOUBLE PRECISION,
    raised_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (case_id, seq)
);

CREATE TABLE IF NOT EXISTS ofr_case_alert_evidence (
    alert_id  VARCHAR(256) NOT NULL REFERENCES ofr_case_alert (alert_id),
    seq       INTEGER      NOT NULL,
    name      VARCHAR(128) NOT NULL,
    value     TEXT         NOT NULL,
    PRIMARY KEY (alert_id, seq)
);

CREATE TABLE IF NOT EXISTS ofr_case_action (
    case_id  VARCHAR(64)  NOT NULL REFERENCES ofr_case (id),
    seq      INTEGER      NOT NULL,
    at       TIMESTAMP WITH TIME ZONE NOT NULL,
    actor    VARCHAR(256) NOT NULL,
    what     VARCHAR(64)  NOT NULL,
    detail   TEXT         NOT NULL,
    PRIMARY KEY (case_id, seq)
);
