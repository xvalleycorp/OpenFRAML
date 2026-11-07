# Transaction Stream (`openframl-stream`)

Transaction Stream connects OpenFRAML to the bank's flow of transactions, in two ways:

- **Stream Service.** It reads the transaction topic and runs payment screening and monitoring on each transaction as it happens. Their alerts go to Case Management (`openframl-case`).
- **Daily Report Job.** It runs after a business day closes. It reads the day back from the same topic and writes that day's reports.

The topic is the record of the day: OpenFRAML keeps no other copy of the transactions.

## The transaction topic

The bank's payment hub publishes each transaction it completes to one Kafka topic (`openframl.transactions` by
default), in the open JSON form `openframl.transaction/1`. The fields are those of the gate's gRPC `Transaction`,
under their names in the event model:

```json
{"schema": "openframl.transaction/1", "eventId": "evt-1", "occurredAt": "2026-10-30T02:00:00Z",
 "channel": "MOBILE", "type": "TRANSFER_OUT", "amount": {"value": "1500000", "currency": "VND"},
 "customerId": "cust-1", "originator": {"institution": "999901", "account": "0123456789"},
 "beneficiary": {"institution": "999902", "account": "5555666677"},
 "session": {"deviceId": "phone-1", "remoteAccessDetected": false, "activeCallDetected": true},
 "beneficiaryName": "Nguyễn Văn Đức", "counterpartyCountry": "SG"}
```

- **Amounts.** Amounts are decimal strings, never JSON numbers.
- **Optional fields.** `beneficiary`, `session`, `beneficiaryName` and `counterpartyCountry` are optional.
- **Record key.** The record key is the originating account, `<institution>/<account>`. Kafka keeps the order within a partition, so this key is what gives monitoring each account's transactions in order.
- **Record timestamp.** The record timestamp should be close to `occurredAt`. Kafka's default, the time the record is produced, is close enough. The Daily Report Job reads each partition until the first record past the end of the day and its margin, so a record stamped far in the future stops the day early on its partition.
- **Retention.** The topic's retention must cover every day that a report may need to be run for again.
- **Confidentiality.** The topic holds account numbers and names. Access to it is restricted like access to the payment hub.

## Stream Service

`StreamService` runs `TransactionStream` with these handlers, in order:
1. Payment screening, when a list directory is set.
2. Monitoring.

Delivery is at least once:
- A record's offset is committed only after every handler has taken it.
- If a handler fails, for example because Case Management's database is unreachable, the stream goes back to that record and tries again after five seconds. Nothing after it is taken in the meantime.
- Every handler is idempotent by event id, so a record taken twice raises nothing new. Monitoring records a transaction only once its alerts have gone.

A record that is not an event is skipped and counted. It is logged with its partition, its offset and the field that
is wrong, never with its content.

### Dead letters

Set `OPENFRAML_STREAM_DEAD_LETTER_TOPIC` to keep the records the stream cannot take. A dead letter holds the record's
key and value unchanged, with the headers `openframl.reason` and `openframl.source`
(`<topic>-<partition>@<offset>`). The stream waits for the broker before it commits past a record, so a record is
never skipped until it is kept. The topic holds what the transaction topic holds, account numbers included, so
restrict access to it in the same way.

An event a handler keeps failing on holds up its partition, because skipping it could lose an alert. That is the
default, and a stopped partition shows in the metrics. With dead letters, `OPENFRAML_STREAM_MAX_ATTEMPTS` sets how
many tries an event gets before the stream gives up on it. The event goes to the dead letters, the handlers after the
failing one do not see it, and the partition moves on. Set a limit only with someone watching the dead letters: an
event given up on has not been fully screened or monitored.

### Metrics

Set `OPENFRAML_STREAM_METRICS_PORT` to serve `GET /metrics` in the Prometheus text format. The metrics are counts and
times only, never an account, a customer or a name.

| Metric | Type | Content |
| --- | --- | --- |
| `openframl_stream_taken_total` | counter | Events every handler has taken |
| `openframl_stream_invalid_total` | counter | Records that are not events |
| `openframl_stream_retries_total` | counter | Times a handler failed and the stream went back |
| `openframl_stream_dead_lettered_total` | counter | Events given up on after the limit of attempts |
| `openframl_stream_last_event_timestamp_seconds` | gauge | The latest `occurredAt` taken: alert when it falls behind |

Set `OPENFRAML_STREAM_VALKEY_URL` to keep monitoring's account activity on Valkey. Every Stream Service process of the
group then shares it, so they can run side by side, and a partition that moves after a rebalance keeps its history.
Without it, the activity is kept in the process, and only one Stream Service may run per consumer group.

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `OPENFRAML_STREAM_KAFKA_BOOTSTRAP` | (required) | Kafka bootstrap servers |
| `OPENFRAML_STREAM_TOPIC` | `openframl.transactions` | The transaction topic |
| `OPENFRAML_STREAM_GROUP` | `openframl-stream` | Consumer group |
| `OPENFRAML_COUNTRY` | `VN` | Country pack |
| `OPENFRAML_CASE_JDBC_URL` | (in memory) | Case Management's database, shared with the Case Service |
| `OPENFRAML_STREAM_SCREENING_DIR` | (no screening) | Directory of screening lists, as for `ScreeningDirectory` |
| `OPENFRAML_STREAM_SCREENING_THRESHOLD` | `0.85` | Lowest name score that raises an alert |
| `OPENFRAML_STREAM_MONITOR` | `true` | Run monitoring |
| `OPENFRAML_STREAM_SUPPRESSION_DAYS` | `7` | How long a scenario that fired for a customer stays quiet |
| `OPENFRAML_STREAM_VALKEY_URL` | (in the process) | Valkey for monitoring's account activity, e.g. `redis://valkey:6379` |
| `OPENFRAML_STREAM_VALKEY_TIMEOUT_MS` | `500` | Valkey command timeout |
| `OPENFRAML_STREAM_DEAD_LETTER_TOPIC` | (none) | Topic for records the stream cannot take |
| `OPENFRAML_STREAM_MAX_ATTEMPTS` | `0` | Tries before an event goes to the dead letters; 0 tries for ever |
| `OPENFRAML_STREAM_METRICS_PORT` | (off) | Port of `GET /metrics` |

## Daily Report Job

`DailyReportJob [yyyy-mm-dd]` runs Regulatory Reporting (`openframl-report`) on one business day. Without a date, it
runs on the day before today.

- `KafkaDayReader` reads, from every partition, the records whose timestamp falls within the day, widened by a margin of one hour on each side. `DailyReporting` keeps only the events of the day. The events are read as they are used, so a large day is never held in memory at once.
- The job writes two files of JSON lines to the output directory:
  - `transaction-reports-<date>.jsonl`, the threshold reports;
  - `suspicious-reports-<date>.jsonl`, the cases concluded as reported that day.
- Each file is written under a temporary name and moved into place, so a reader never sees half a file. Running a day again replaces its files with the same content.
- The files are readable by their owner only, because threshold reports hold account numbers.
- The job logs how many transactions it read. It also logs how many it found duplicated, how many belonged to other days, and how many had no exchange rate. A foreign-currency transaction without a rate is compared with no threshold, so set the rates.

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `OPENFRAML_STREAM_KAFKA_BOOTSTRAP`, `OPENFRAML_STREAM_TOPIC` | | As above |
| `OPENFRAML_COUNTRY`, `OPENFRAML_CASE_JDBC_URL` | | As above |
| `OPENFRAML_REPORT_OUT_DIR` | `.` | Where the report files go |
| `OPENFRAML_REPORT_RATES` | (none) | `currency,rate` per line: national currency per unit, for the day |
| `OPENFRAML_REPORT_HOLIDAYS` | (none) | One ISO date per line: the public holidays that deadlines skip |
| `OPENFRAML_REPORT_MARGIN_MINUTES` | `60` | How far beyond the day to read the topic |
| `OPENFRAML_REPORT_DETECTION` | `APPROVAL` | When a suspicion counts as detected: `APPROVAL` or `FIRST_ALERT` |
