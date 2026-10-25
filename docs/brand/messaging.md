# OpenFRAML messaging

OpenFRAML does not win a feature-by-feature comparison with the established commercial FRAML products, and it does
not try to. It wins on two things a bank feels every year: **what the platform costs** and **who controls it**.
Every page, deck and pitch leads with these two; the technical design is the proof behind them, not the headline.

## Positioning

| | English | Tiếng Việt |
| --- | --- | --- |
| One line | Fraud and AML protection without the licence bill, on a platform the bank controls. | Phòng chống gian lận và rửa tiền không tốn phí bản quyền, trên nền tảng ngân hàng tự làm chủ. |
| Headline | Fraud and AML protection without the licence bill. | Phòng chống gian lận và rửa tiền, không tốn phí bản quyền. |

## Key selling point 1: cost

Pay for servers, not licences.

| Claim | Proof |
| --- | --- |
| No licence fee: not per transaction, account, module or year. Volume grows, the bill does not. | Apache 2.0; every dependency on a licence allowlist checked by the build |
| Ordinary x86 servers, no GPU, no appliance. | In-memory design; sizing comes from the public benchmark, not from a quote |
| Customers clear their own doubtful payments. | `CHALLENGE` asks the customer to confirm instead of queueing every doubtful payment for an analyst |
| Compliance work done once per country, shared by every bank. | Country packs carry thresholds, lists and report templates |

## Key selling point 2: control

The bank owns the platform.

| Claim | Proof |
| --- | --- |
| Your rules: your team writes and changes them, no change request to a vendor. | Decision tables are data; effective dates select the version in force; maker-checker approval is on the roadmap |
| Your data: it never leaves the bank. | On-premise deployment; audit records hold no personal data |
| Your code: read it, audit it, change it, fork it. No lock-in, no exit project. | Apache 2.0, public repository |
| Your explanations: every decision can be shown to an auditor or the regulator. | Each decision records rule and model versions and reason codes, and can be replayed; models never override mandatory rules |

## Rules for every claim

- **No invented numbers.** No savings percentages, no vendor prices, no analyst-hours saved until they are measured
  (the cost model and shadow-mode metrics in the [roadmap](../roadmap.md) will provide them). Use `[PLACEHOLDER]`
  where a figure is needed.
- **Planned is planned.** OpenFRAML is pre-release; say "on the roadmap" for anything not built.
- **Say what it still costs.** Running it yourself takes servers, a team that knows Java and Kafka, and support. Saying
  so is what makes the cost claim credible to a bank.
- **No vendor names** in comparisons; compare with "a licensed platform" and say "often", not "always".
- **Technical proof second.** Latency, benchmark and architecture support the two points; they do not replace them.
