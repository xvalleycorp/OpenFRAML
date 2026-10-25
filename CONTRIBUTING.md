# Contributing to OpenFRAML

Thank you for your interest. Bug reports, documentation, rules, connectors and code are all welcome.

## Developer Certificate of Origin (DCO)

Every commit needs a `Signed-off-by` line, certifying that you may contribute it under
[DCO 1.1](https://developercertificate.org/):

```bash
git commit -s -m "gate: hold payments to listed beneficiaries when the store is down"
```

Pull requests with commits missing the sign-off are not merged.

## Workflow

1. For a large change (a new API, new behaviour, a new module), open an issue first to agree on the approach.
2. Branch from `main`, and write the code with its tests.
3. Run `./scripts/install-rulestone.sh` once, then `mvn verify` and `python3 scripts/check-docs.py`.
4. Open a pull request describing the problem and the solution.

## Conventions

- Java 21. Code, APIs, field names, log messages and Javadoc are in English.
- Documentation is bilingual: English in `docs/` (and the root `README.md`), Vietnamese in `docs/vi/`. A pull request
  that changes one language changes the other; CI checks that both have the same pages and the same headings.
- The core names no country. Currencies, time zones, regulators, schemes and legal thresholds belong in a country
  pack (see [docs/country-packs.md](docs/country-packs.md)).
- Reason codes are stable `UPPER_SNAKE_CASE`, with a message in English and in each language of the pack.
- Every rule of a decision table has a `.stone-test.json` case, including both sides of each threshold.
- Never log or record personal data: no full account numbers, ID numbers, names or amounts. `AccountRef.toString()`
  masks the account number; `AccountRef.key()` is for lookups only.
- Examples and test data use invented codes, never a real bank's: bank codes `999901` and `999902`, the invented
  country `XA`, the ISO 4217 test currency `XTS`.
- New dependencies must have a licence on the allowlist in `pom.xml` (Apache-2.0, MIT, MIT-0, BSD-3-Clause). Avoid
  projects that are BSL, SSPL, AGPL or "source-available" (for example Redpanda, Aerospike, Neo4j Community, recent
  Redis, Elasticsearch, MinIO). CI runs `mvn -Plicense-check verify`. The one exception is the Benchmark module
  (`openframl-benchmark`): it uses JMH (GPL-2.0 with the Classpath Exception) as a measuring tool, is never deployed
  or distributed, and is left out of the check.
- Call modules by their name and give the code alongside it the first time, e.g. Payment Gate (`openframl-gate`);
  the names are listed in the [README](README.md#modules).
- Commit prefixes by module: `model:`, `gate:`, `server:`, `vn:`, `benchmark:`, `docs:`, `build:`, `ci:`.

## RuleStone

OpenFRAML uses RuleStone as an external user would: a pinned version (a pinned commit until RuleStone is on Maven
Central), problems reported as GitHub issues, fixes contributed as pull requests under RuleStone's own
`CONTRIBUTING.md`. OpenFRAML does not fork RuleStone. Track the issues it opens with the label `upstream:rulestone`;
a workaround in OpenFRAML's code links to its issue and is removed once the fix is released.

## Reporting a vulnerability

Never in a public issue. See [SECURITY.md](SECURITY.md).
