# Regulatory Reporting (`openframl-report`)

Regulatory Reporting finds what a bank must report to its financial intelligence unit: transactions that reach a
legal threshold, and suspicious transactions that an investigation concluded must be reported. Thresholds and
deadlines are decision tables in the country pack, each version in force from the date of the regulation that sets
it, so a day from before a change is still judged by the rules of that day.

OpenFRAML produces the content of each report in an open JSON form. Filling the authority's own form and sending it
through its channel is a separate step. The customer's identity details (name, ID number, address) come from the
bank's own records at that step.

The Daily Report Job of Transaction Stream ([`openframl-stream`](stream.md)) runs all of this on a closed day, read
back from the bank's transaction topic, and writes the reports to files.

## Threshold reports, day by day

`DailyReporting` runs once a business day has closed, on that day's transactions:

1. Each transaction goes through `report_transaction`. Each rule that fires puts the transaction in that report for
   its customer, for example a large electronic transfer.
2. Each customer's cash of the day (deposits and withdrawals) is summed and goes through `report_daily`. Each rule
   that fires puts all of the customer's cash transactions of the day in that report, so cash split into smaller
   amounts is reported like one large amount.
3. Each report's deadline is the business date plus the rule's `dueWorkingDays`, counted in working days.

- **Business day.** The business day is the country pack's (for Vietnam, `Asia/Ho_Chi_Minh`). Events of other days are skipped and counted.
- **Repeat runs.** Running a day again gives the same reports. Transactions are told apart by event id, and a report's id is its kind, customer and day (`LARGE_CASH:cust-1:2026-10-30`).
- **Foreign currency.** Thresholds are compared in the national currency. Foreign-currency amounts are converted with the bank's `ExchangeRates`. An amount without a rate is compared with no threshold and is counted in the run's `unconverted`, so it can be looked into.
- **Holidays.** `WorkingDays` takes the weekend and the bank's list of public holidays. Holidays move from year to year, so the bank supplies them.

Facts for `report_transaction`:

| Fact | Type | Content |
| --- | --- | --- |
| `transaction.amount` | money | As the transaction gives it |
| `transaction.nationalAmount` | money | In the national currency; absent without a rate |
| `transaction.usdAmount` | money | In US dollars, the currency of FATF's wire transfer thresholds; absent without a rate |
| `transaction.counterpartyCountry` | string | The counterparty's country, if the bank set it |
| `transaction.crossBorder` | bool | The counterparty's country is set and is not the pack's |
| `transaction.type`, `.channel` | string | From the event model |
| `transaction.direction`, `.cash` | string, bool | `IN` or `OUT`; a cash deposit or withdrawal |

Facts for `report_daily`, in the national currency:

| Fact | Type | Content |
| --- | --- | --- |
| `cashIn.count`, `.amount` | int, money | Cash deposits of the day |
| `cashOut.count`, `.amount` | int, money | Cash withdrawals of the day |
| `cash.count`, `.amount` | int, money | Both together |

Both tables use hit policy `COLLECT`, declare the int output `dueWorkingDays` (1 to 99) and give every rule a reason
code, which names the report. A policy that breaks any of this is never built.

## Suspicious transaction reports

`SuspiciousTransactionReport.fromCases` takes the cases closed as `REPORTED`, which in Case Management
(`openframl-case`) means an analyst proposed the conclusion and a different supervisor approved it. Each report holds:

- the customer;
- the approved conclusion with the analyst's reason;
- when it was approved;
- every alert with its evidence;
- the case's audit trail.

The alerts name transactions by event id and hold no account numbers, so the filing step looks up the details in the
bank's systems.

### Deadlines

`SuspiciousDeadlines` gives each case two deadlines, from the country pack's `report_suspicious` table in force on
the day of the case's first alert:

- **From the transaction.** `fromTransaction` working days after the business date of the first alert's
  transaction. It is known as soon as a case opens, so analysts can order their work by it.
- **From detection.** `fromDetection` working days after the bank detected the suspicion. When detection is, is the
  bank's reading of its law, set with `Detection`:
  - `APPROVAL`, the default, counts it when a supervisor approves the conclusion that the case must be reported.
    By then the bank knows of the suspicion at the latest.
  - `FIRST_ALERT` counts it when the first alert was raised. It is the strictest reading.

A report is due by the earlier of the two. A case concluded after the deadline from the transaction is flagged, so
the bank sees which reports were late against it, whatever reading it applies. The Daily Report Job writes both
deadlines with each report, under `deadline`, and logs a warning for each late case.

Vietnam's table follows Article 37(2) of the Anti-Money Laundering Law 14/2022/QH15: three working days from the
transaction, or one working day from detection. Article 37(3) sets 24 hours, with a report to the competent
authority as well, when a transaction a customer requests appears linked to a crime. The table does not cover that
case, which needs an analyst's judgement. These deadlines are to be confirmed against the official gazette.

The customer must never learn that a report was made; in Vietnam, Article 40 of the Anti-Money Laundering Law forbids
disclosing it. Nothing in this module is meant for a channel the customer sees.

## The report format

`ReportJson` writes each report as one JSON object:

- `openframl.report.transaction/1` for threshold reports;
- `openframl.report.suspicious/1` for suspicious transaction reports.

Field names are stable within a schema version.

A threshold report holds full account numbers, since the authority needs them. It is the only OpenFRAML output that
does. Report files are confidential, and they are kept for as long as the law says (five years in Vietnam). The
reports' `toString` still masks account numbers, so a report that ends up in a log does not leak them.

```json
{"schema": "openframl.report.transaction/1", "id": "LARGE_CASH:cust-1:2026-10-30", "kind": "LARGE_CASH",
 "subject": "cust-1", "businessDate": "2026-10-30", "dueBy": "2026-11-02",
 "tables": ["report_daily@1@sha256:..."],
 "transactions": [{"eventId": "evt-1", "occurredAt": "2026-10-30T02:00:00Z", "type": "CASH_DEPOSIT",
   "channel": "BRANCH", "amount": {"value": "450000000", "currency": "VND"},
   "account": {"institution": "999901", "account": "0123456789"}}]}
```

## Vietnam thresholds

The Vietnam Country Pack (`openframl-vn`) ships version 1 of both tables, in force from 1 December 2023.

| Report | Table | Reported when | Deadline |
| --- | --- | --- | --- |
| `LARGE_CASH` | `report_daily` | One customer's cash in one day comes to 400,000,000 VND or more | 1 working day |
| `ELECTRONIC_TRANSFER` | `report_transaction` | A domestic transfer, out or in, of 500,000,000 VND or more | 1 working day |
| `INTERNATIONAL_TRANSFER` | `report_transaction` | A transfer to or from another country, out or in, of 1,000 USD or more | 1 working day |
| Suspicious transaction report | `report_suspicious` | A case concluded as reported | 3 working days from the transaction, 1 from detection |

The sources are:
- Decision 11/2023/QĐ-TTg: the 400,000,000 VND threshold, which replaced the 300,000,000 VND of Decision 20/2013/QĐ-TTg.
- Circular 09/2023/TT-NHNN, replaced from 1 November 2025 by Circular 27/2025/TT-NHNN, which keeps the thresholds: 500,000,000 VND for domestic transfers and 1,000 USD for international ones.
- Article 37(1) of the Anti-Money Laundering Law 14/2022/QH15: the deadline of one working day for reports filed electronically.

These were checked against published summaries and republished texts, not yet against the official gazette. Three
points are still to be confirmed there, and each table's description says which apply to it:

- **Cash summed together.** Whether deposits and withdrawals of a day are summed together. The table sums them, which reports more rather than less.
- **Which side reports.** Which institution reports which side of a transfer. The table reports both.
- **International threshold date.** When the 1,000 USD threshold took effect. The table applies it from 1 December 2023, with the domestic one.
- **Excluded transactions.** The circular excludes some transactions, such as savings withdrawals and loan repayments. The event model cannot tell them apart yet, so the bank leaves them out of the day's events.

A transfer is international when its `counterpartyCountry` is set and is not `VN`. A cross-border payment without
the country is treated as domestic, so the bank must set it on every one.

Both sides of each threshold have cases in `openframl-vn/src/test/rules/report_daily.stone-test.json` and
`report_transaction.stone-test.json`.

## Next

On the [roadmap](roadmap.md):
- the authority's forms and electronic submission, as a country pack converter checked against the official templates;
- the excluded transaction types;
- the 24-hour report of Article 37(3) in Vietnam.
