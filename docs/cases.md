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

## Next

Watchlist Screening already raises alerts for payment beneficiaries whose names may be listed (see
[Watchlist Screening](screening.md#screening-payments)). On the [roadmap](roadmap.md): a durable `CaseStore`, an API
and screens for analysts, and suspicious transaction reports from `REPORTED` cases through Regulatory Reporting
(`openframl-report`).
