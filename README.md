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

> **Status: pre-release, phase 1.** The inline gate works as a library and is tested; the gRPC service, the Valkey
> and Kafka integrations and the benchmark come next. Nothing is published to Maven Central yet. See the
> [roadmap](docs/roadmap.md).

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

| Module | Purpose | Phase | Status |
| --- | --- | --- | --- |
| [`openframl-model`](openframl-model) | Common transaction event model; country pack SPI | 1 | Usable |
| [`openframl-gate`](openframl-gate) | Inline decision before a payment is sent: `ALLOW`, `CHALLENGE`, `HOLD`, `DENY` | 1 | Library usable; gRPC service next |
| [`openframl-vn`](openframl-vn) | Vietnam country pack: sample gate rules, reasons in English and Vietnamese | 1 | Sample rules |
| `openframl-features` | Behaviour profiles and velocity, computed with Apache Flink | 2 | Planned |
| `openframl-monitor` | AML and fraud scenarios after the transaction | 2 | Planned |
| `openframl-screen` | Screening against sanctions, PEP and national lists | 2 | Planned |
| `openframl-case` | Alerts, investigation, four-eyes approval | 2 | Planned |
| `openframl-report` | Regulatory reports in the templates of each country pack | 2 | Planned |
| `openframl-ml` | Model serving, champion/challenger, shadow mode | 3 | Planned |

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

- [The gate: steps, facts, fallback policy](docs/gate.md)
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
