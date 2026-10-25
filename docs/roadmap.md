# Roadmap

Phase 1 builds only the inline path and its benchmark: ultra-low latency is the largest technical risk, so it is
proven before the rest is built. Durations are estimates for a small team and will be fixed with the staffing plan.

| Phase | Months (estimate) | Content | Gate to the next phase |
| --- | --- | --- | --- |
| 1. Inline core | 0–4 | Common event model; gate with RuleStone embedded; velocity on Valkey; Vietnam sample rules; load benchmark | p99 under 30 ms at 20,000 TPS |
| 2. Monitoring | 4–10 | Flink features; AML scenarios; cases; reports and screening; SIMO connector | Reports in the regulatory templates |
| 3. Pilot | 10–16 | Shadow mode at a bank; models in shadow; gate in production; VOBS integration | Pilot in production |
| 4. Scale | From 16 | Models in production; graph analysis; LLM investigation assistant; more countries | |

## Phase 1

- [x] Common transaction event model, versioned (`openframl-model`)
- [x] Country pack SPI; the core tested against an invented country
- [x] Gate as a library: watchlist first, profiles, velocity, model with a time budget, decision table, fallback
      policy, audit record without personal data (`openframl-gate`)
- [x] Node-local velocity counters, watchlist replica and profile store in memory
- [x] Vietnam sample rules with a test for every rule and threshold (`openframl-vn`)
- [ ] gRPC service with per-call deadlines; routing by originating account
- [ ] Profiles and durable velocity on Valkey, with an in-process Caffeine cache
- [ ] Decision records to Kafka (`GateRecordSink`)
- [ ] Hot reload of decision tables after maker-checker approval
- [ ] ONNX Runtime `ScoreProvider`
- [ ] JMH benchmark per step, and a synthetic load generator for the 20,000 TPS target

## Upstream issues for RuleStone

OpenFRAML is a demanding RuleStone user for latency and volume. What it needs from RuleStone goes upstream as GitHub
issues, tracked here with the label `upstream:rulestone`; in the meantime OpenFRAML works around them in its own
code, with a link to the issue. The first four are on the critical path to the phase 1 gate.

| Need | RuleStone today | Proposal |
| --- | --- | --- |
| Timeout per evaluation | Planned | Needed for the gate's latency budget |
| Public performance numbers | No benchmark | A JMH suite measuring p99 and allocation under concurrency |
| Shadow and canary rule versions | Planned | Needed for champion/challenger |
| Decision records to Kafka | `AuditSink` for Kafka planned | OpenFRAML writes one first, then proposes it upstream |
| Large-value threshold in the AML example | `aml_transaction_monitoring` uses 300,000,000 VND | Two versions before and after 1 Dec 2023, showing effective dating |
| Masking of account and ID numbers in logs | `DataMasker` planned | Needed for personal data protection |
| Screening against large lists | List items are normalised on every evaluation | Accept pre-normalised lists |
| Model scores as attested inputs | Design proposal | Pattern A (score as an input) before Pattern B |
| Maker-checker, hash-chained audit | Planned | Mandatory for compliance rules |
| Backtesting on historical data | Planned | Needed to tune AML thresholds |
