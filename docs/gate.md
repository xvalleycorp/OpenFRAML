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
decided it. Backtesting or a shadow run before activation is next on the [roadmap](roadmap.md).

### Admin service and every node

With several gate nodes, a change is approved once and every node follows it. The Gate Service serves the
`PolicyAdmin` gRPC API
([`openframl/gate/admin/v1/policy_admin.proto`](../openframl-gate-server/src/main/protobuf/openframl/gate/admin/v1/policy_admin.proto))
on its own port:

1. **Who.** Only over mutual TLS. The caller is the common name of a client certificate issued by the bank's admin
   CA; the bank lists which names are makers (`OPENFRAML_ADMIN_MAKERS`) and which are checkers
   (`OPENFRAML_ADMIN_CHECKERS`). A call without a certificate from that CA does not get past the handshake; a name on
   neither list is refused.
2. **Propose.** A maker sends the whole policy, every version of every table, with a reason. It is compiled and
   checked at once, so a checker never reviews a policy that cannot run. The proposal records the policy in force at
   that moment.
3. **Review and approve.** A checker reads the proposed tables (`GetPolicyTables`) and approves, naming the policy id
   they reviewed; the approval fails if it does not match. The maker can never decide their own proposal, even with
   both roles. A proposal can only be approved while the policy it was made against is still in force: of two
   changes prepared in parallel, the second must be proposed again on top of the first.
4. **Every node follows.** The approval is stored in the `PolicyRepository` (`ValkeyPolicyRepository` with Valkey,
   where the approval is one atomic script). Each node's `PolicySync` reads it every 2 seconds, and at start-up,
   compiles the tables and puts the policy in force with `Gate.activate`'s guarantees. A node only trusts tables that
   hash to the approved id: tables changed after approval are refused, and the node stays where it is.
5. **Roll back.** `ProposeRollback` proposes the policy in force before the last approved change; it is approved like
   any change.

Whoever can write the repository's keys can change what the gates run: restrict them with Valkey ACLs to the gate
nodes. Without Valkey, the repository lives in the process, for a single node.

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

## Models

The ONNX Scorer (`openframl-onnx`) runs a fraud model in the gate's process with ONNX Runtime. A model trained in
any framework (XGBoost, LightGBM, scikit-learn, PyTorch) and exported to ONNX can be used; the score is one input of
the decision table, never the decision itself.

The model file carries its own contract in its metadata, so the file is all that is deployed:

| Metadata key | Required | Content |
| --- | --- | --- |
| `openframl.features` | Yes | The features it was trained on, comma-separated, in input order |
| `openframl.model_version` | Yes | Recorded with every decision, e.g. `fraud-xgb-3` |
| `openframl.output` | No | The output holding the fraud probability; by default the last output |

The input is one float tensor `[1, features]`. The output is the fraud probability: a float tensor `[1]`, `[1, 1]`,
or `[1, 2]` with the fraud class last, or scikit-learn's list of class-to-probability maps. `OnnxScoreProvider.load`
checks the metadata and the input shape and runs the model once, so a model that does not fit fails when it is
loaded, not on a payment. Each call uses one ONNX thread: the gate scores many payments at once.

`ScoreFeatures` names the features a model can ask for. A name is never renamed or changed in meaning, only added:

| Features | Content |
| --- | --- |
| `amount`, `log_amount` | The payment's amount, and `log(1 + amount)` |
| `count_10m`, `count_24h`, `amount_24h`, `log_amount_24h`, `distinct_beneficiaries_24h` | Velocity, including this payment |
| `originator_age_days`, `beneficiary_age_days` | Account age at the business date; `-1` without a profile |
| `originator_risk_high`, `beneficiary_risk_high` | Risk rating `HIGH` |
| `beneficiary_known`, `same_institution` | Paid before; beneficiary at the same bank |
| `has_device`, `device_known`, `remote_access`, `active_call` | Session signals |
| `hour_of_day` | Hour in the country pack's business time zone |
| `channel_<channel>`, `type_<type>` | One flag per channel (`channel_mobile`...) and transaction type (`type_transfer_out`...) |

## Stores

The in-memory stores (`openframl-gate`, package `memory`) keep everything on the node: for tests, benchmarks and
single-node trials. The Valkey Store (`openframl-valkey`) keeps profiles and velocity in Valkey, or a Redis-compatible
server, so that they survive a restart and every node sees the same counters.

- **Layout.** `ValkeyLayout` names the keys: an account's profile is a hash (`ofr:acct:<account>`, fields `openedOn`
  and `risk`), the beneficiaries it has paid and the devices a customer has used are sets, and its attempts of the
  last 24 hours are a sorted set scored by time. The stream layer writes this layout; until it exists, a batch job
  can load profiles with `ValkeyProfileLoader`.
- **Profiles: one round trip, or none.** `ValkeyProfileStore` sends every command the cache cannot answer at once.
  A Caffeine cache in the gate's process keeps account profiles, found or not, for 60 seconds by default, and a known
  beneficiary or device once it is seen. A change in Valkey reaches the gate within that time.
- **Velocity: atomic per account.** `ValkeyVelocityStore` sends each attempt with a short script that adds it, trims
  the account's set to 24 hours and 10,000 attempts, and returns the rest; the gate counts them, in exact decimals.
  The script runs atomically, so two nodes deciding payments of one account see each other. An attempt is stored
  under its event id, so a retried call is counted once. Beneficiaries are stored as a truncated SHA-256, not as
  account numbers; that keeps them out of plain sight but is not anonymisation, so access to Valkey must be
  restricted like access to the core banking data.
- **Failures.** One shared connection; a command that takes longer than its timeout (5 ms in the Gate Service) or
  is sent while the connection is down fails at once, and the gate applies its fallback policy.

## Configuration

| Builder option | Default | Purpose |
| --- | --- | --- |
| `profiles` | Required | Precomputed profiles |
| `velocity` | Required | Short-term counters: `InMemoryVelocityStore` on the node, `ValkeyVelocityStore` shared |
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
- **Routing.** With in-memory stores, route calls by originating account (consistent hashing on
  `originator.institution` and `originator.account`), so each account's counters live on one node and a payment sees
  the one before it. With the Valkey stores, any node can serve any account; routing by account still keeps each
  node's cache warm.
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
| `OPENFRAML_GATE_VALKEY_URL` | Not set: empty in-memory stores | Valkey for profiles and velocity, e.g. `redis://valkey:6379` |
| `OPENFRAML_GATE_VALKEY_TIMEOUT_MS` | `5` | How long a Valkey command may take |
| `OPENFRAML_GATE_PROFILE_CACHE_SECONDS` | `60` | How long the gate caches a profile |
| `OPENFRAML_GATE_MODEL` | Not set: rules alone | Path to an ONNX fraud model |
| `OPENFRAML_GATE_MODEL_BUDGET_MS` | `5` | The longest the gate waits for a score |
| `OPENFRAML_GATE_POLICY_SYNC_SECONDS` | `2` | How often the node checks for an approved policy |
| `OPENFRAML_ADMIN_PORT` | Not set: no admin service | Port of the policy admin service, mutual TLS only |
| `OPENFRAML_ADMIN_TLS_CERT`, `OPENFRAML_ADMIN_TLS_KEY` | Required with the port | The server's certificate chain and PKCS#8 key, PEM |
| `OPENFRAML_ADMIN_TLS_CLIENT_CA` | Required with the port | The CA that issues admin client certificates |
| `OPENFRAML_ADMIN_MAKERS`, `OPENFRAML_ADMIN_CHECKERS` | Required with the port | Certificate common names, comma-separated |

Without `OPENFRAML_GATE_VALKEY_URL` the server starts with empty in-memory stores, for trials and benchmarks only.
The watchlist starts empty until List Screening (`openframl-screen`) loads it. An application can build its own
`Gate` and serve it with `GateServer.start`.
