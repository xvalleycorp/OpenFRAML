# Case Management (`openframl-case`)

Case Management is where a person decides what detectors found. Watchlist Screening (`openframl-screen`),
Transaction Monitoring (`openframl-monitor`, planned) and the Payment Gate (`openframl-gate`) raise alerts; analysts
investigate them; a supervisor approves every conclusion. No conclusion is reached by one person alone, and every
step is recorded.

## Alerts and cases

An `Alert` names its source (`screening`, `monitoring`, `gate`), what it found (e.g. `SANCTIONS_NAME_MATCH`), the
subject it is about (the bank's customer id or another stable reference), the transaction that raised it, a score,
and the evidence an analyst needs: for a screening match, the list, its version, the entry and the matched name.

`CaseService.raise` puts the alert in the subject's case that is not closed, or opens one. An analyst therefore sees
everything about a subject at once, instead of one case per alert. An alert id already in a case changes nothing, so
a detector can resend an alert without creating work twice. Alerts about one subject arriving at the same moment
still land in one case.

## Investigation and approval

| Step | Who | Status after |
| --- | --- | --- |
| An alert opens the case | The detector | `OPEN` |
| The case is assigned | An analyst taking it for themselves, or a supervisor assigning it | `IN_REVIEW` |
| Notes on what was checked | Analysts and supervisors | Unchanged |
| A conclusion is proposed: `FALSE_POSITIVE`, `CONFIRMED` or `REPORTED`, with a reason | The assigned analyst | `AWAITING_APPROVAL` |
| The conclusion is approved | A supervisor who did not propose it | `CLOSED` |
| The conclusion is returned, with a reason | A supervisor | `IN_REVIEW` |

- **Four eyes.** The person who proposes a conclusion can never approve it, even with both roles.
- **New evidence wins.** An alert that arrives while a conclusion awaits approval sends the case back to review: the
  conclusion did not see it.
- **History.** Every action is kept with who did it and when: alerts arriving, assignments, notes, proposals,
  approvals and returns. This is the audit trail an examiner reads.
- **One writer at a time.** A `CaseStore` refuses a save based on a case that has changed since it was read, so two
  analysts acting at once cannot overwrite each other.

## Storing cases

`JdbcCaseStore` keeps cases in a relational database through JDBC, in plain tables that an examiner or a report can
query with SQL: `ofr_case` for each case and its current state, `ofr_case_alert` and `ofr_case_alert_evidence` for
its alerts, and `ofr_case_action` for its history. PostgreSQL is the reference database; the schema is in
[`schema-postgresql.sql`](../openframl-case/src/main/resources/vn/io/dxa/openframl/cases/jdbc/schema-postgresql.sql),
and the bank supplies the connection pool; the Case Service ships with the PostgreSQL driver.

- Each save is one transaction. The case row carries a version, the length of its history, and is updated only if
  the version is still the one that was read; otherwise the save is refused and the action can be retried.
- Alerts, evidence and the history are only ever inserted, never updated or deleted, through the store.
- A unique index allows at most one case that is not closed per subject, so alerts raised at the same moment on
  different nodes still open one case.
- `InMemoryCaseStore` remains for tests and trials.

## Case Service

The Case Service (`openframl-case-server`) serves Case Management over gRPC
([`openframl/cases/v1/case_service.proto`](../openframl-case-server/src/main/protobuf/openframl/cases/v1/case_service.proto)),
over mutual TLS only. The caller is the common name of a client certificate issued by the bank's CA, and the bank
lists which names are analysts, supervisors and detectors:

| Caller | May |
| --- | --- |
| Detector (e.g. a screening node) | `RaiseAlert` only |
| Analyst | Read cases, take a case, add notes, propose a conclusion on a case assigned to them |
| Supervisor | Read cases, assign cases, add notes, approve or return a conclusion they did not propose |

Every call is recorded in the case's history under the caller's name. A refusal has its own status:
`UNAUTHENTICATED` without a certificate, `PERMISSION_DENIED` for a role the caller does not have,
`FAILED_PRECONDITION` for a step the case is not ready for, `ABORTED` when someone else changed the case first (read
it again and retry).

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `OPENFRAML_CASE_PORT` | `9190` | Port |
| `OPENFRAML_CASE_TLS_CERT`, `OPENFRAML_CASE_TLS_KEY` | Required | The server's certificate chain and PKCS#8 key, PEM |
| `OPENFRAML_CASE_TLS_CLIENT_CA` | Required | The CA that issues client certificates |
| `OPENFRAML_CASE_ANALYSTS`, `OPENFRAML_CASE_SUPERVISORS`, `OPENFRAML_CASE_DETECTORS` | None | Certificate common names, comma-separated |
| `OPENFRAML_CASE_JDBC_URL` | Not set: cases in memory, for trials only | The case database; the PostgreSQL driver is included, another database's driver goes on the class path |

## Next

Watchlist Screening already raises alerts for payment beneficiaries whose names may be listed (see
[Watchlist Screening](screening.md#screening-payments)). On the [roadmap](roadmap.md): screens for analysts, and suspicious transaction reports from `REPORTED` cases through Regulatory Reporting
(`openframl-report`).
