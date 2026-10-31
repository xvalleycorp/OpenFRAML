# Transaction Monitoring (`openframl-monitor`)

Transaction Monitoring runs the AML scenarios after each transaction. It keeps each account's recent activity, sums
it up, runs it through the country pack's scenario table, and raises an alert for Case Management
(`openframl-case`) for every scenario that fires. Scenarios are decision tables, not code: a bank changes a threshold
or adds a scenario in its own copy of the table, with effective dates, and tests it like the gate's tables.

## How a transaction is monitored

1. The movement is recorded on the bank's account (the account the event model calls the originator, both ways):
   in for `TRANSFER_IN` and `CASH_DEPOSIT`, out for the other types. The counterparty is kept only as a hash. A
   transaction delivered twice is recorded once.
2. The account's activity of the last 30 days, this transaction included, becomes the facts below.
3. `monitor_scenarios` is evaluated as of the transaction's business date, hit policy `COLLECT`.
4. Each scenario that fires raises an alert about the customer, with the scenario, its severity and message, the
   table version and the activity it saw; never an account number. A scenario that keeps firing for a customer
   raises one alert per suppression period (7 days by default): the open case already holds it.

Transactions of one account must be processed in order. The Stream Service of Transaction Stream
([`openframl-stream`](stream.md)) feeds monitoring from the bank's transaction topic, keyed by account; a batch job
can also call `TransactionMonitor.process`. The activity is kept for 35 days, in the process (`InMemoryActivityStore`)
or on Valkey (`ValkeyActivityStore` in the Valkey Store, `openframl-valkey`), where every Stream Service process shares
it. A movement is recorded only once its alerts have gone, so an alert that could not be raised is raised when the
transaction comes again.

## Facts

Amounts are summed in the transaction's currency only; windows end at the transaction.

| Fact | Type | Content |
| --- | --- | --- |
| `transaction.amount`, `.type`, `.channel` | money, string, string | This transaction |
| `transaction.direction`, `.cash` | string, bool | `IN` or `OUT`; a cash deposit or withdrawal |
| `account.daysSinceLastActivity` | int | Days since the account's previous movement; absent for its first |
| `cashIn24h.count`, `.amount`, `.maxAmount` | int, money, money | Cash deposits in 24 hours: how many, their total, the largest |
| `in24h.count`, `.amount`, `.distinctCounterparties` | int, money, int | Money in over 24 hours |
| `in48h.amount`, `out48h.amount` | money | Money in and out over 48 hours |
| `flow48h.outToIn` | decimal | Out divided by in over 48 hours; absent when nothing came in |
| `in7d.count`, `.amount`; `out7d.count`, `.amount` | int, money | Money in and out over 7 days |
| `in30d.count`, `.amount`, `.distinctCounterparties` | int, money, int | Money in over 30 days |
| `out30d.count`, `.amount`, `.distinctCounterparties` | int, money, int | Money out over 30 days |

## Vietnam scenarios

The Vietnam Country Pack (`openframl-vn`) ships `monitor_scenarios` version 1, in force from 1 December 2023. Its
thresholds illustrate the typologies and are not legal thresholds, except for `CASH_STRUCTURING`.

| Scenario | Severity | Fires when |
| --- | --- | --- |
| `CASH_STRUCTURING` | `HIGH` | Two or more cash deposits in 24 hours reach 400,000,000 VND together while each stays below it |
| `PASS_THROUGH` | `HIGH` | 100,000,000 VND or more came in within 48 hours and 90% or more of it has gone out |
| `MANY_SENDERS` | `MEDIUM` | Money from 10 or more different counterparties in 24 hours |
| `DORMANT_REACTIVATED` | `MEDIUM` | 50,000,000 VND or more on an account with no activity for 180 days or more |

`CASH_STRUCTURING` uses the large-value reporting threshold set by Decision 11/2023/QĐ-TTg: 400,000,000 VND from
1 December 2023, replacing the 300,000,000 VND of Decision 20/2013/QĐ-TTg. The figure and the date were checked
against several published summaries of the decision, not yet against the official gazette; confirm them there before
relying on them, as for any legal threshold. Reporting large-value transactions themselves is the job of Regulatory
Reporting ([`openframl-report`](reporting.md)), not of an alert.

Every scenario and both sides of every threshold have a case in
`openframl-vn/src/test/rules/monitor_scenarios.stone-test.json`.

## Next

On the [roadmap](roadmap.md): backtesting a scenario on past transactions before it is approved, and windows kept as
running totals by Behaviour Profiles (`openframl-features`), for accounts with so many transactions that reading 30
days of them for each one costs too much.
