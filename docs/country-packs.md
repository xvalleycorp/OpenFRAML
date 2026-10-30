# Country packs

A country pack holds everything that differs between countries, so the core never names one. Vietnam is the first
pack, not the only one: adding a country means adding a pack, not changing the core.

## What a pack contains

| Content | Phase 1 | Later |
| --- | --- | --- |
| Business time zone and national currency | Yes | |
| Decision tables for the gate (`gate_decision`, `gate_fallback`) | Yes | |
| Reason messages in English and the country's languages | Yes | |
| Legal thresholds with effective dates (large-value, electronic transfer reporting) | | Phase 2 |
| AML scenarios for monitoring (`monitor_scenarios`), apart from the gate's tables | | Phase 2: started, see [Transaction Monitoring](monitoring.md) |
| Report templates and submission channels | | Phase 2 |
| List sources (sanctions, police, regulator) and name normalisation | | Phase 2 |
| Switch adapters, business calendar | | Phase 2 |

A pack implements `CountryPack` and lists itself in `META-INF/services/vn.io.dxa.openframl.pack.CountryPack`.
`ResourceCountryPack` reads tables and messages from class path resources next to the pack's class; reason messages
are UTF-8 files `reasons_<language>.properties`, with English required as the fallback.

Decision tables carry effective dates, and a pack keeps the past versions of every table, so a decision of any day
can be replayed with the rules in force that day. When a regulation changes, the pack gets a new table version with
the regulation's effective date; the code does not change.

## Keeping the core neutral

The core's own tests run against an invented country, `XA`, with the ISO 4217 test currency `XTS`, much as
RuleStone's examples use the placeholder country codes `XA` and `XB`. Another test reads the core's sources and fails
on a currency, time zone, regulator or scheme of a real country. Neither test touches Vietnam, so a dependency of the
core on Vietnam shows up as a failing build.

## Vietnam Country Pack (`openframl-vn`)

`openframl-vn` (version `2026.10.0`) uses the zone `Asia/Ho_Chi_Minh` and the currency `VND`. Its gate tables are
samples: their thresholds illustrate the typologies seen in Vietnam and are not legal thresholds. Each bank tunes its
own copy and keeps it confidential, because whoever knows the rules and thresholds knows how to avoid them.

| Rule | Action | Typology |
| --- | --- | --- |
| `WATCHLISTED_BENEFICIARY` | `HOLD` | Beneficiary on a list of suspicious accounts; mandatory, no score lowers it |
| `REMOTE_ACCESS` | `HOLD` | Remote-control app during the payment (fake apps that take over the phone) |
| `CALL_TO_NEW_BENEFICIARY` | `CHALLENGE` | Phone call while paying a new beneficiary (callers posing as the police or a government office) |
| `NEW_DEVICE_LARGE` | `CHALLENGE` | 10,000,000 VND or more from a new device |
| `YOUNG_BENEFICIARY` | `CHALLENGE` | 20,000,000 VND or more to an account under 30 days old (rented or bought accounts) |
| `NEW_BENEFICIARY_LARGE` | `CHALLENGE` | 50,000,000 VND or more to a new beneficiary |
| `RAPID_SUCCESSION` | `CHALLENGE` | 5 or more attempts in 10 minutes |
| `MANY_BENEFICIARIES` | `CHALLENGE` | 10 or more beneficiaries in 24 hours |
| `REPEATED_NEAR_LIMIT` | `CHALLENGE` | Consecutive transfers near the 499,999,999 VND fast-transfer limit, just under the 500,000,000 VND reporting threshold |
| `FRAUD_SCORE_HIGH`, `FRAUD_SCORE_ELEVATED` | `HOLD`, `CHALLENGE` | Model score from 0.9, and from 0.7 up to 0.9 |

The sample prefers `CHALLENGE` to `DENY`: blocking a genuine payment is costly too, and in a scam the customer
confirms the payment themselves, so a warning at the right moment is what stops it. Every rule and both sides of
every threshold have a case in `openframl-vn/src/test/rules`; the build fails if any rule is never matched.

Legal thresholds and report templates come in phase 2, each checked against the official gazette before it is
added. The analysis so far relies on secondary sources.

## Adding a country

1. Create a module `openframl-<country code>` with a class extending `ResourceCountryPack` and its service file.
2. Write `gate_decision` and `gate_fallback` for the country's typologies, against the facts in
   [the gate](gate.md#facts), with a `.stone-test.json` case for every rule.
3. Add reason messages in English and the country's languages for every reason code.
4. Run `mvn verify`. Nothing in the core changes; if it has to, open an issue first.
