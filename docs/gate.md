# Payment Gate (`openframl-gate`)

The Payment Gate decides what to do with a payment after the customer confirms it and before the payment hub sends it to the
switch. Instant payments cannot be recalled, so this is the only moment to stop one. The target is p99 under 30 ms,
measured from receiving the request to returning the decision; it will be verified by the phase 1 benchmark.

## Steps and latency budget

Everything the gate reads is precomputed and held in memory or a local key-value store. RuleStone and the model run
inside the gate's process. Nothing in the hot path queries a relational database or makes a network call that is
not listed here.

| Step | What happens | Budget (p99) |
| --- | --- | --- |
| Receive | Decode the request | 1 ms |
| Watchlist | Look the beneficiary up in the local replica of the lists of suspicious accounts | 2 ms |
| Profiles | Read the originator, the beneficiary and the device from the profile store, in one call | 3 ms |
| Velocity | Count this attempt and read the originator's short-term counters | 2 ms |
| Model | Score the payment; a score that is late is dropped | 5 ms |
| Rules | Evaluate the decision table `gate_decision` | 2 ms |
| Reply | Return the decision; the audit record is published asynchronously | 1 ms |
| Reserve | GC pauses, queueing, bursts | 14 ms |

The watchlist check runs first, before anything that can fail or time out. The velocity counters are updated before
the decision is returned, and requests are routed to nodes by originating account, so a payment always sees the
one before it. The caller passes its deadline with each request; the gate skips the model when too little time is
left and decides with the rules alone.

## Decisions

| Decision | Meaning | What the bank does |
| --- | --- | --- |
| `ALLOW` | Let the payment through | Send it to the switch |
| `CHALLENGE` | Ask for more | Show a scam warning, re-authenticate, or have the customer confirm again |
| `HOLD` | Keep it back for a quick review | Open a high-priority case, contact the customer |
| `DENY` | Refuse it | Tell the customer; report if required |

The decision table uses hit policy `COLLECT`: every rule that fires proposes an action, the most severe one wins, and
the outcome keeps the reason codes of all of them. Reason codes are for the bank's staff; they are never shown to
customers, because AML reasons are confidential.

## Facts

The gate passes these facts to both of its tables. A table declares the fields it uses; facts it does not declare
are ignored. A field that the gate could not fill (the step did not run, failed, or does not apply) is left unset,
so tables test optional fields with `has(...)`.

| Fact | Type | Set when |
| --- | --- | --- |
| `transaction.amount` | money | Always |
| `transaction.type`, `transaction.channel` | string | Always |
| `originator.accountAgeDays` | int | The profile store has the originating account |
| `originator.riskRating` | string | The account has an AML risk rating |
| `beneficiary.watchlisted` | bool | The payment has a beneficiary and the watchlist check ran |
| `beneficiary.watchlistSource` | string | The beneficiary is listed |
| `beneficiary.known` | bool | The profile lookup succeeded: the originator has paid this beneficiary before |
| `beneficiary.accountAgeDays` | int | The profile store has the beneficiary account |
| `beneficiary.sameInstitution` | bool | The payment has a beneficiary |
| `device.remoteAccess`, `device.activeCall` | bool | Always (`false` when the channel cannot tell) |
| `device.known` | bool | The session has a device id and the profile lookup succeeded |
| `velocity.count10m`, `velocity.count24h`, `velocity.distinctBeneficiaries24h` | int | The velocity step succeeded; counts include this attempt |
| `velocity.amount24h` | money | The velocity step succeeded; in the currency of this payment |
| `score.fraud` | decimal, 0 to 1 | A score arrived in time |
| `score.modelVersion` | string | A score arrived in time |
| `failure` | string | Only for `gate_fallback`: `TIMEOUT`, `STORE_UNAVAILABLE` or `POLICY_ERROR` |

The business date that selects the table version in force is the payment's `occurredAt` in the country pack's time
zone. Replaying a payment therefore uses the rules that were in force when it was made.

## Fallback policy

When the deadline passes before the decision table runs, a store fails, or the decision table fails, the gate
evaluates `gate_fallback` instead, with the facts gathered so far. The bank writes there which payments fail open
and which fail closed. The Vietnam pack, for example:

| Situation | Decision | Why |
| --- | --- | --- |
| Beneficiary is listed | `HOLD` (fail closed) | The watchlist check ran first and does not depend on what failed |
| Small payment to a known beneficiary | `ALLOW`, marked for review (fail open) | Low risk; do not interrupt the customer |
| Anything else | `CHALLENGE` | Safer than refusing; the customer can still complete the payment |

If `gate_fallback` fails too, a built-in last resort holds a listed beneficiary and challenges everything else, with
reason code `GATE_FALLBACK_FAILED`.

Both tables are checked when the gate is built. A missing table, a table without the string output `action`, or a
rule that sets an unknown action stops the build instead of failing a payment.

## Changing the policy

A bank changes its rules and thresholds often, because fraud patterns change quickly; it does so without a release or
a restart. The two tables form the gate's policy (`GatePolicy`): the country pack's samples by default, or the bank's
own tables, kept confidential in a directory of `.stone.json` files with every past version.

1. The change is prepared as a new set of tables and compiled. Compiling checks everything that can be checked
   before a payment arrives: RuleStone type-checks every rule, and the gate requires both tables and known actions.
   A policy that fails a check is never built.
2. A second person approves it. `Approval` names the maker, the checker and the reason; the checker can never be the
   maker.
3. `Gate.activate` records the change with the `PolicyAudit`, then swaps the policy in one step. If the audit cannot
   record the change, the change is not applied. Payments that start from then on use the new policy; a payment
   already being decided finishes on the policy it started with.
4. `Gate.rollback` returns to the previous policy in one step, approved and recorded the same way.

Each policy has an id, a SHA-256 hash of its tables' content, and each decision records the version of the table that
decided it. Backtesting or a shadow run before activation, and an admin API with authenticated makers and checkers,
are next on the [roadmap](roadmap.md).

## Records

Every decision produces one `GateRecord`, handed to the record sink before `decide` returns. The sink publishes it
asynchronously (to Kafka) and must not block. A record holds the event id, the outcome, the rule versions with
RuleStone's own decision records, the model version and score, the list the beneficiary was found on, and the time
spent in each step. Like RuleStone's records, it holds no input values: no account numbers, names or amounts.

`GateRecordJson` writes a record as one line of JSON, schema `openframl.gate.record/1`, with RuleStone's decision
records in RuleStone's own JSON form. The format is open: a bank reads its records without OpenFRAML. Fields are not
removed or renamed within a schema version.

The Kafka Connector (`openframl-kafka`) publishes them: `KafkaGateRecordSink` sends each record to a topic, keyed by
event id, with acknowledgement from every in-sync replica, idempotent retries and lz4 compression. `accept` only puts
the record on a bounded queue (100,000 by default, about 10 seconds at 10,000 TPS); one sender thread does the rest,
because a Kafka producer can block its caller and the decision path must not wait. When the queue is full the record
is dropped, never the decision: `dropped()` and `failed()` count what is lost and should be alerted on.

## Configuration

| Builder option | Default | Purpose |
| --- | --- | --- |
| `profiles` | Required | Precomputed profiles |
| `velocity` | Required | Short-term counters; `InMemoryVelocityStore` keeps them on the node |
| `watchlist` | No list | Local replica of the lists of suspicious accounts |
| `scoreProvider` | No model | Fraud model; without one, every decision takes the rules-only branch |
| `recordSink` | Drop records | Where audit records go |
| `modelBudget` | 5 ms | The longest the gate waits for a score |
| `ruleReserve` | 2 ms | Time kept back for the decision table after scoring |
| `executor` | Virtual threads | Where scoring runs |
| `policy` | The country pack's samples | The tables to start with, e.g. `GatePolicy.fromDirectory` |
| `policyAudit` | Log the change | Records every policy change before it takes effect |

## gRPC service

The Gate Service (`openframl-gate-server`) serves the Payment Gate over gRPC, with the API in
[`openframl/gate/v1/gate.proto`](../openframl-gate-server/src/main/protobuf/openframl/gate/v1/gate.proto): one call,
`GateService.Decide`, from a `Transaction` to a `DecideResponse`.

- **Deadline.** The caller sets a deadline on every call. The service gives the gate the time remaining less a
  reply margin (1 ms by default). A call without a deadline gets the default budget (30 ms).
- **Routing.** Route calls by originating account (consistent hashing on `originator.institution` and
  `originator.account`), so each account's velocity counters live on one node and a payment sees the one before it.
- **Errors.** An incomplete or invalid transaction is rejected with `INVALID_ARGUMENT`, naming the field. The message
  never repeats an account number.
- **Amounts.** Exact decimals as text without an exponent (`"1500000"`, `"12.50"`); floating point never reaches
  the gate.

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `OPENFRAML_COUNTRY` | `VN` | Country pack |
| `OPENFRAML_GATE_RULES_DIR` | The pack's samples | Directory of the bank's own `*.stone.json` tables |
| `OPENFRAML_GATE_PORT` | `9090` | Port |
| `OPENFRAML_GATE_DEFAULT_BUDGET_MS` | `30` | Budget of a call without a deadline |
| `OPENFRAML_GATE_REPLY_MARGIN_MS` | `1` | Kept back from the caller's deadline for the reply |
| `OPENFRAML_GATE_KAFKA_BOOTSTRAP` | Not set: records are not kept | Kafka brokers for decision records |
| `OPENFRAML_GATE_RECORDS_TOPIC` | `openframl.gate.records` | Topic for decision records |

Until the Valkey stores are in, the server starts with empty in-memory stores: it is for trials and benchmarks, not
production. An application can build its own `Gate` and serve it with `GateServer.start`.
