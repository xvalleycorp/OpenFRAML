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

- [x] Event Model: common transaction event model, versioned (`openframl-model`)
- [x] Country pack SPI; the core tested against an invented country
- [x] Payment Gate as a library: watchlist first, profiles, velocity, model with a time budget, decision table, fallback
      policy, audit record without personal data (`openframl-gate`)
- [x] Node-local velocity counters, watchlist replica and profile store in memory
- [x] Vietnam Country Pack: sample rules with a test for every rule and threshold (`openframl-vn`)
- [x] Gate Service: gRPC with per-call deadlines (`openframl-gate-server`)
- [x] Profiles and durable velocity on Valkey, with an in-process Caffeine cache: Valkey Store (`openframl-valkey`)
- [x] Decision records to Kafka, in an open JSON format: Kafka Connector (`openframl-kafka`)
- [x] Policy changes without a restart: activate and roll back in one step, four-eyes approval, recorded before they
      take effect (`openframl-gate`)
- [x] Admin API for policy changes, makers and checkers authenticated by mutual TLS, followed by every node:
      `PolicyAdmin` gRPC service and `PolicySync` (`openframl-gate-server`)
- [ ] Backtest or shadow run of a proposed policy before it is approved
- [x] ONNX Runtime `ScoreProvider`, the model's contract in its metadata: ONNX Scorer (`openframl-onnx`)
- [x] Benchmark: JMH and an open-loop gRPC load test on a synthetic bank (`openframl-benchmark`); first results in
      [Benchmark](benchmark.md)
- [ ] Routing by originating account across gate nodes
- [ ] The 20,000 TPS target measured on server hardware with Generational ZGC

## Cost and control

A bank weighs a FRAML platform on what it costs to own and on how much of it the bank controls, as much as on its
features. These items make both measurable and written down; the phase is when each can be done.

| Item | Phase | Content |
| --- | --- | --- |
| Cost model (`docs/cost.md`) | 1 | Total cost of ownership by group: licence, servers, operations team, alert handling, rule changes, integration, support; the design choices that lower each, and the costs that remain |
| Sizing guide | 1 | Nodes per TPS from the server benchmark, with high availability and disaster recovery |
| Deployment profiles | 2 | Small (batch profiles, no Flink), medium and large, each with its components and team skills |
| Cost metrics in shadow mode | 3 | Cost per decision, alerts per 1,000 payments, false positive rate, time from a rule proposal to the rule in force |
| Support model | 3 | What the community covers and what paid support covers |
| Exit and data ownership | 2 | Rules, cases and decision records exportable in open formats; no part of the platform that only a vendor can run |

## Upstream issues for RuleStone

OpenFRAML is a demanding RuleStone user for latency and volume. What it needs from RuleStone goes upstream as GitHub
issues, tracked here with the label `upstream:rulestone`; in the meantime OpenFRAML works around them in its own
code, with a link to the issue. The first four are open and on the critical path to the phase 1 gate.

| Need | RuleStone today | Proposal |
| --- | --- | --- |
| Timeout per evaluation ([RuleStone#7](https://github.com/xvalleycorp/RuleStone/issues/7)) | Planned | Needed for the gate's latency budget |
| Public performance numbers ([RuleStone#8](https://github.com/xvalleycorp/RuleStone/issues/8)) | No benchmark | A JMH suite measuring p99 and allocation under concurrency |
| Shadow and canary rule versions ([RuleStone#9](https://github.com/xvalleycorp/RuleStone/issues/9)) | Planned | Needed for champion/challenger |
| Decision records to Kafka ([RuleStone#10](https://github.com/xvalleycorp/RuleStone/issues/10)) | `AuditSink` for Kafka planned | OpenFRAML writes one first, then proposes it upstream |
| Large-value threshold in the AML example | `aml_transaction_monitoring` uses 300,000,000 VND | Two versions before and after 1 Dec 2023, showing effective dating |
| Masking of account and ID numbers in logs | `DataMasker` planned | Needed for personal data protection |
| Screening against large lists | List items are normalised on every evaluation | Accept pre-normalised lists |
| Model scores as attested inputs | Design proposal | Pattern A (score as an input) before Pattern B |
| Maker-checker, hash-chained audit | Planned | Mandatory for compliance rules |
| Backtesting on historical data | Planned | Needed to tune AML thresholds |
