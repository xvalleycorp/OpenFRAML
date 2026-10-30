# Cost model

What it costs a bank to own OpenFRAML, group by group: what the bank pays for, which design choices lower it, and
what still has to be paid. Total cost of ownership decides a FRAML platform as much as its features, so this page
is written to be filled in with the bank's own figures, not ours.

OpenFRAML is pre-release. This page gives no savings figure and no price: those come from the bank's own numbers
and, later, from the metrics measured in shadow mode (see [Measuring it](#measuring-it)). Cells marked
`[PLACEHOLDER]` are for the bank to fill.

## Cost groups

| Group | What the bank pays for | What lowers it | What remains |
| --- | --- | --- | --- |
| Licence | Nothing: Apache 2.0, no fee per transaction, account, module, node or year | Every dependency is on a licence allowlist (Apache-2.0, MIT, MIT-0, BSD-2-Clause, BSD-3-Clause), checked by the build | Optional paid support, if the bank wants it |
| Servers | Gate nodes, Valkey, Kafka; later the stream and monitoring layers | Ordinary x86 servers, no GPU, no appliance; profiles and counters in memory | Servers for high availability and a second site; their power, rack space and maintenance |
| Operations team | People who run Java services, Valkey and Kafka | Standard components the bank's platform team may already run; container images, health checks and metrics are on the roadmap | The team itself, on call |
| Alert handling | Analysts who review doubtful payments and AML alerts | `CHALLENGE` asks the customer to confirm instead of queueing an analyst; every decision carries reason codes; models only add a score | Analysts for what remains, and for AML cases |
| Rule changes | Time from a proposed rule to the rule in force | Rules are data: the bank's team proposes, a checker approves, every node follows within seconds, without a release; effective dates select the version in force | Review, testing and approval time; backtests before approval are on the roadmap |
| Integration | Connecting the payment hub, the core banking feeds and the reports | One gRPC call for the inline decision; decision records on Kafka in an open JSON format; country packs carry the regulatory templates | The bank's own adapters, the integration project, its testing |
| Compliance content | Thresholds, lists and report templates per country | Done once per country in a shared, versioned country pack | Keeping the bank's own tables and lists current |
| Exit | Leaving the platform, or changing who runs it | Rules, profiles and decision records in open formats; the code is the bank's to read, change and fork | Migrating the data to whatever comes next |

## Sizing

The number of gate nodes comes from the throughput one node sustains within the latency target, measured on the
bank's hardware with the [benchmark](benchmark.md), not from a quote. The server measurement is not published yet;
until it is, the formula is all this page gives.

```
nodes per site = ceil(peak TPS × headroom / TPS per node) + 1   (one node can fail at peak)
sites          = 2                                              (the second site takes over)
```

| Input | Where it comes from | Value |
| --- | --- | --- |
| Peak TPS | The bank's payment hub, busiest second of the year (salary days, holidays) | `[PLACEHOLDER]` |
| Headroom | The bank's growth plan for the next 3 years, e.g. 1.5 | `[PLACEHOLDER]` |
| TPS per node | Benchmark on the bank's server type with Generational ZGC, p99 under the latency target | `[PLACEHOLDER]` |

Shared components, per site:

| Component | Phase | Typical layout | Notes |
| --- | --- | --- | --- |
| Gate nodes | 1 | From the formula above | Stateless apart from caches; any node serves any account with the Valkey stores |
| Valkey | 1 | A primary and a replica, or a small cluster | Profiles, velocity counters, the approved policy; size by accounts and attempts kept for 24 hours |
| Kafka | 1 | 3 brokers | Decision records; retention per the bank's audit policy |
| Stream layer (Flink) | 2 | On the roadmap | Profiles and features; a small bank can compute profiles in batch instead |
| Monitoring, cases, reports | 2 | On the roadmap | |

## Total cost worksheet

A yearly figure for each group, filled from the bank's own numbers. Compare it with the same worksheet for the
platform the bank runs today, including the licence and the vendor's change requests.

| Group | Year 1 | Each later year | How the bank estimates it |
| --- | --- | --- | --- |
| Licence | 0 | 0 | |
| Paid support (optional) | `[PLACEHOLDER]` | `[PLACEHOLDER]` | Quotes, if the bank wants a support contract |
| Servers, both sites | `[PLACEHOLDER]` | `[PLACEHOLDER]` | Nodes from [Sizing](#sizing) × the bank's cost per server, plus Valkey and Kafka |
| Operations team | `[PLACEHOLDER]` | `[PLACEHOLDER]` | Share of the platform team's time, on-call included |
| Alert handling | `[PLACEHOLDER]` | `[PLACEHOLDER]` | Alerts per year × time per alert × analyst cost |
| Rule changes | `[PLACEHOLDER]` | `[PLACEHOLDER]` | Changes per year × hours from proposal to rule in force |
| Integration | `[PLACEHOLDER]` | `[PLACEHOLDER]` | Mostly year 1 |
| Total | `[PLACEHOLDER]` | `[PLACEHOLDER]` | |

## Measuring it

These metrics turn the worksheet's estimates into measurements. They come from a shadow run at the bank, where
OpenFRAML decides every payment alongside the current system without acting on it (phase 3 on the
[roadmap](roadmap.md)).

| Metric | What it shows |
| --- | --- |
| Cost per decision | Servers and operations divided by decisions |
| Alerts per 1,000 payments | The load on analysts |
| False positive rate | The share of those alerts that were not fraud |
| Time from a rule proposal to the rule in force | What a rule change costs, and how fast the bank can react to a new fraud pattern |

## What it still costs

Running OpenFRAML is not free. A bank still pays for servers at two sites, a team that knows Java, Valkey and Kafka
and is on call, analysts for the alerts that remain, the integration with its own systems, and keeping its rules and
lists current. What it does not pay is a licence that grows with its volume, or a vendor for every change to its own
rules.
