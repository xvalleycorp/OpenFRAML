# Benchmark (`openframl-benchmark`)

The phase 1 gate is p99 under 30 ms at 20,000 TPS on commodity x86 servers. The Benchmark module measures it in two
ways, both on a deterministic synthetic bank with the Vietnam Country Pack. The target is not verified yet: the
first runs below were on a laptop, where the load generator and the server share one machine.

## What is measured

| Tool | Measures | Network |
| --- | --- | --- |
| `GateBenchmark` (JMH, sample time) | One decision in process: watchlist, profiles, velocity, optional model, decision table | None |
| `LoadTest` (open loop) | End to end over gRPC, at a fixed rate; with an embedded server, also the gate's time per step | gRPC over loopback, or to a remote server |

The synthetic bank has 100,000 customers with profiles, four known beneficiaries and a known device each, 1% mule
accounts opened in the last 20 days, and a tenth of the mules on the watchlist. The payment mix follows the
typologies of the Vietnam pack: 80% ordinary, 8% small and 4% large to new beneficiaries, 3% phone-call scams to mule
accounts, 2% new devices, 2% listed beneficiaries, 1% remote access. Each JMH thread pays for its own slice of
customers, as a node does for the accounts routed to it.

`LoadTest` sends on a fixed schedule whether or not earlier payments have been answered, and measures each latency
from the moment the payment was due. A slow server therefore shows in the percentiles instead of slowing the sender
(no coordinated omission).

## Running

```bash
mvn -pl openframl-benchmark -am -DskipTests package
java -jar openframl-benchmark/target/openframl-benchmark.jar GateBenchmark -t 8
java -XX:+UseZGC -XX:+ZGenerational -Xms4g -Xmx4g \
  -cp openframl-benchmark/target/openframl-benchmark.jar vn.io.dxa.openframl.benchmark.LoadTest \
  --rate=20000 --seconds=60 --warmup=15
```

`LoadTest` options: `--rate` (payments per second), `--seconds` (measured), `--warmup`, `--deadline-ms`,
`--customers`, `--target=host:port` for a server on another machine.

Use a current JDK 21 update. On macOS 26, JDK 21.0.2 and 22.0.2 crash under load with ZGC (`SIGBUS`, misaligned
address in compiled code); the runs below therefore used G1.

## First results

Apple M2 (8 cores), 16 GB, macOS 26.6, JDK 22.0.2, G1 with a 4 GB heap, 25 October 2026.

`GateBenchmark`, 4 threads, latency of one decision in microseconds:

| Model | p50 | p90 | p99 | p99.9 | p99.99 |
| --- | --- | --- | --- | --- | --- |
| None (rules only) | 16.8 | 19.7 | 50.6 | 129 | 19,300 |
| Stub (answers at once, on another thread) | 28.6 | 46.1 | 79.5 | 138 | 19,110 |

`LoadTest`, embedded server, latency in milliseconds:

| Rate | Answered | Client p50 | Client p99 | Client p99.9 | Gate p99 | Gate p99.9 |
| --- | --- | --- | --- | --- | --- | --- |
| 10,000 TPS, 20 s | 100% | 0.096 | 2.67 | 21.0 | 0.042 | 0.108 |
| 20,000 TPS, 30 s | 50% (rest `DEADLINE_EXCEEDED`) | 0.102 | 55.4 | 79.5 | 0.084 | 1.05 |

What the numbers say so far:

- **The decision itself is not the bottleneck.** In process, p99.9 is 0.13 ms, far inside the 30 ms budget. The
  decision table takes most of it (about 16 µs at p50); stores and watchlist take a few microseconds.
- **GC pauses make the tail.** The p99.99 of about 19 ms, and the client p99.9 of 21 ms at 10,000 TPS, match G1
  pauses (16 pauses in the 10,000 TPS run, the longest 19, 25 and 38 ms). This is why the design specifies
  Generational ZGC, with pauses under 1 ms.
- **The 20,000 TPS run is not conclusive.** The sender fell behind schedule 19,219 times: one 8-core laptop ran the
  sender, the client and the server, so the machine saturated before the gate did.
- **Handing scoring to another thread costs about 12 µs** at p50, inside the 5 ms model budget.

## Next measurements

1. Linux x86 servers of the sizing profile (16 vCPU, 64 GB), current Temurin 21, Generational ZGC.
2. The load generator on separate machines, several gRPC channels, against one node, then a cluster routed by
   account.
3. The Valkey stores and the Kafka record sink in the path once they are in.
4. Results published here, and the RuleStone numbers sent upstream with the benchmark issue.
