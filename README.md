<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/brand/openframl-logo-on-dark.svg">
  <img src="docs/brand/openframl-logo.svg" alt="OpenFRAML" height="56">
</picture>

# OpenFRAML

[![CI](https://github.com/xvalleycorp/OpenFRAML/actions/workflows/ci.yml/badge.svg)](https://github.com/xvalleycorp/OpenFRAML/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

[Tiếng Việt](docs/vi/README.md)

OpenFRAML is an open-source fraud and anti-money-laundering (FRAML) platform for banks. It stops fraudulent payments
before they leave the bank and covers the AML obligations — transaction monitoring, watchlist screening, case
management and regulatory reporting — in one system, with [RuleStone](https://github.com/xvalleycorp/RuleStone) as
the decision engine.

> **Status: pre-release, phase 1.** The Payment Gate works as a library and as a gRPC service, with a benchmark;
> the Valkey and Kafka integrations come next, and the latency target is still to be verified on server hardware.
> Nothing is published to Maven Central yet. See the [roadmap](docs/roadmap.md).

- **Two paths.** An inline path decides a payment in a few tens of milliseconds, inside the payment flow. An
  asynchronous path handles the volume for monitoring and reporting. The inline call is the only synchronous one.
- **Rules decide, models score.** A model's score is one input to a decision table; mandatory rules are never
  overridden by a model. Every decision records the rule version and the model version.
- **State outside the engine.** RuleStone is stateless and deterministic. Velocity and behaviour profiles are
  precomputed and handed to it as facts, so every decision can be replayed.
- **Country-neutral core.** Everything specific to a country — legal thresholds, report templates, lists, schemes,
  time zone, languages — lives in a versioned country pack. Vietnam (`openframl-vn`) is the first.
- **Open source end to end.** Apache 2.0, every dependency on a licence allowlist checked by the build, runs
  on-premise on commodity x86 servers, no GPU required.

## Modules

| Module | Code | Purpose | Phase | Status |
| --- | --- | --- | --- | --- |
| [Event Model](openframl-model) | `openframl-model` | Common transaction event model; country pack SPI | 1 | Usable |
| [Payment Gate](openframl-gate) | `openframl-gate` | Inline decision before a payment is sent: `ALLOW`, `CHALLENGE`, `HOLD`, `DENY` | 1 | Usable as a library |
| [Gate Service](openframl-gate-server) | `openframl-gate-server` | The Payment Gate over gRPC; the caller's deadline is the gate's time budget | 1 | Experimental |
| [Kafka Connector](openframl-kafka) | `openframl-kafka` | Publishes the gate's decision records to Kafka, in an open JSON format | 1 | Usable |
| [Valkey Store](openframl-valkey) | `openframl-valkey` | Profiles and durable velocity on Valkey, with an in-process cache | 1 | Usable |
| [ONNX Scorer](openframl-onnx) | `openframl-onnx` | Fraud models in the gate's process with ONNX Runtime, from named features | 1 | Usable |
| [Vietnam Country Pack](openframl-vn) | `openframl-vn` | Sample gate rules for Vietnam, reasons in English and Vietnamese | 1 | Sample rules |
| [Benchmark](openframl-benchmark) | `openframl-benchmark` | JMH benchmark and open-loop gRPC load test on a synthetic bank | 1 | Usable |
| Behaviour Profiles | `openframl-features` | Behaviour profiles and velocity, computed with Apache Flink | 2 | Planned |
| Transaction Monitoring | `openframl-monitor` | AML and fraud scenarios after the transaction | 2 | Planned |
| [Watchlist Screening](openframl-screen) | `openframl-screen` | Screening against sanctions, PEP and national lists; the gate's account lists | 2 | Started: name screening and account lists |
| Case Management | `openframl-case` | Alerts, investigation, four-eyes approval | 2 | Planned |
| Regulatory Reporting | `openframl-report` | Regulatory reports in the templates of each country pack | 2 | Planned |
| Model Serving | `openframl-ml` | Model serving, champion/challenger, shadow mode | 3 | Planned |

Modules are called by their name; the code is for lookups, Maven coordinates and package names.

## Getting started

Requires JDK 21, Maven 3.9 or newer, and git. RuleStone is not on Maven Central yet, so install it from source once
before building:

```bash
./scripts/install-rulestone.sh  # installs RuleStone at a pinned commit into the local Maven repository
mvn verify                      # builds and tests every module
mvn -Plicense-check verify      # checks the licences of all dependencies
```

Deciding a payment with the gate and the Vietnam pack:

```java
Gate gate = Gate.builder(CountryPack.forCountry("VN"))
        .profiles(profileStore)          // precomputed profiles: account age, known beneficiaries and devices
        .velocity(new InMemoryVelocityStore())
        .watchlist(watchlist)            // local replica of the lists of suspicious accounts
        .recordSink(kafkaPublisher)      // one audit record per decision, without personal data
        .build();

GateOutcome outcome = gate.decide(event, Duration.ofMillis(30));
// outcome.decision() == CHALLENGE, outcome.reasonCodes() == [ACTIVE_CALL_NEW_BENEFICIARY, ...]
```

## Documentation

- [Payment Gate: steps, facts, fallback policy, gRPC service](docs/gate.md)
- [Benchmark](docs/benchmark.md)
- [Cost model: what owning OpenFRAML costs, and what it still costs](docs/cost.md)
- [Watchlist Screening: account lists for the gate, name screening](docs/screening.md)
- [Country packs](docs/country-packs.md)
- [Roadmap](docs/roadmap.md)
- [Contributing](CONTRIBUTING.md) · [Security policy](SECURITY.md)

## Related projects

- [RuleStone](https://github.com/xvalleycorp/RuleStone): the decision engine. OpenFRAML uses released versions and
  reports what it needs through GitHub issues; it does not fork RuleStone.
- [VOBS](https://github.com/xvalleycorp/vobs): Vietnam Open Banking Stack. Its API gateway calls the gate for
  payments initiated by third parties.

## License

[Apache License 2.0](LICENSE).
