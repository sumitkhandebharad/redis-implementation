# HTTP Load Test Report

## Summary

These measurements came from `scripts/load_test.py` against the local product API. All reported requests completed with HTTP 200. The results show a useful capacity trend for this particular setup: throughput stayed near 8,000 requests/second at 20 and 100 workers for a warmed key, then fell as concurrency increased sharply. At 1,000 workers, latency rose substantially; at 3,000 workers, throughput declined further and tail latency reached seconds.

Treat the figures as observations from one environment, not as production capacity guarantees. The host hardware, app/JVM settings, Redis/PostgreSQL placement, background load, warm-up duration, and repeatability were not captured alongside the measurements. The Python client itself also contributes overhead, especially at high thread counts.

## Measurements

| Mode | Concurrency | Duration | Requests | Throughput (req/s) | Median (ms) | p95 (ms) | p99 (ms) | Max (ms) | HTTP outcomes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| Warm, one key | 20 | 30.00 s | 239,469 | 7,981.54 | 2.35 | 4.01 | 5.05 | 36.91 | 200: 239,469 |
| Random, 100 keys | 20 | 30.00 s | 242,735 | 8,090.28 | 2.32 | 3.95 | 4.91 | 37.12 | 200: 242,735 |
| Warm, one key | 100 | 30.01 s | 243,369 | 8,110.00 | 11.59 | 21.75 | 28.00 | 56.25 | 200: 243,369 |
| Warm, one key | 1,000 | 30.18 s | 133,150 | 4,412.38 | 198.29 | 533.69 | 740.85 | 1,675.63 | 200: 133,150 |
| Random, 100 keys | 3,000 | 30.71 s | 47,660 | 1,551.82 | 184.37 | 2,013.18 | 3,288.81 | 6,198.43 | 200: 47,660 |
| Random, 100 keys | 1,000 | 1.17 s | 5,031 | 4,294.17 | 179.81 | 467.25 | 662.79 | 949.51 | 200: 5,031 |
| Random, 100 keys | 3,000 | 30.54 s | 52,928 | 1,733.23 | 124.18 | 1,691.28 | 2,400.02 | 5,730.53 | 200: 52,928 |

Minimum latency ranged from 0.38 ms to 2.05 ms across the runs.

## Reading the results

- At 20 workers, the warmed single-key and warmed 100-key workloads were close: 7,981.54 and 8,090.28 req/s. This indicates similar observed throughput for these two cache-hit shapes at that concurrency.
- At 100 workers on one warm key, throughput was 8,110 req/s, close to the 20-worker result, while median latency increased from 2.35 ms to 11.59 ms. Added concurrency did not improve the observed rate and increased waiting time.
- At 1,000 workers, the warm run reached 4,412.38 req/s with p95 latency of 533.69 ms. The separate 1-second random run reached 4,294.17 req/s, with p95 latency of 467.25 ms. That short run is less comparable to 30-second runs and should be repeated for a stable comparison.
- At 3,000 workers, the two random runs reached 1,551.82 and 1,733.23 req/s. Their medians were 124–184 ms, while p95 values were 1.69–2.01 seconds and p99 values were 2.40–3.29 seconds. This indicates substantial queuing or resource contention at this offered concurrency in the measured setup.
- Every request returned 200, so these runs did not show API errors. Successful status codes do not imply acceptable latency; the high-concurrency tail latencies may still be unsuitable for an interactive workload.

The load generator uses a fixed number of synchronous Python threads. It is a closed-loop test: each worker waits for its response before sending another request. Consequently, the offered request rate falls as latency rises. Thread scheduling, socket behavior, application request-thread limits, JVM behavior, Redis, PostgreSQL, and host resources can all contribute. These measurements alone do not identify the bottleneck.

## Reproduction

Start the application, PostgreSQL, and Redis, then run from the same client environment used for these measurements:

```bash
python3 scripts/load_test.py --concurrency 20 --duration 30 --mode warm
python3 scripts/load_test.py --concurrency 20 --duration 30 --mode random
python3 scripts/load_test.py --concurrency 100 --duration 30 --mode warm
python3 scripts/load_test.py --concurrency 1000 --duration 30 --mode warm
python3 scripts/load_test.py --concurrency 3000 --duration 30 --mode random
```

The random mode creates and warms 100 products by default. Runs create database records and leave them behind. The output includes min, median, p95, p99, max latency, throughput, and status counts.

## Recommended next measurements

1. Repeat each workload at least three times and compare median throughput and tail latency across runs.
2. Record the machine model, OS, JVM version and options, Spring/Tomcat settings, Redis/PostgreSQL configuration, and whether client and server share a host.
3. Run the load generator on a separate host to reduce client-side CPU and thread contention with the application.
4. Capture CPU, heap/GC pauses, active HTTP threads, Hikari pool usage, Redis command latency, and PostgreSQL activity during each run.
5. Use a pool size larger than the expected hot set when assessing key distribution, and add separate database-miss and write tests for those paths. Do not interpret the warm-cache figures as PostgreSQL capacity.
6. Define an acceptable p95/p99 latency objective before treating any concurrency level as a target.

No production capacity or scaling conclusion should be made from these runs without the missing environment data and repeat measurements.
