# Redis Cache

A Spring Boot 3 / Java 21 example of cache-aside product reads backed by PostgreSQL, with Redis used only as a disposable performance layer.

## Architecture and behavior

`GET /api/products/{id}` reads `redis-cache:v2:product:{id}` from Redis. A valid JSON value returns immediately without touching PostgreSQL. A miss, malformed value, or Redis outage falls back to PostgreSQL; a successful database result is JSON-serialized and cached with an explicit TTL. Redis read and write failures are logged and counted but do not fail a successful database read. PostgreSQL remains the source of truth.

Cache values are JSON `ProductResponse` records serialized through the shared Jackson mapper. The key prefix includes `v2` so incompatible cache formats can be invalidated by changing the namespace. RedisTemplate uses string serializers and has no Java native serialization or polymorphic typing.

The default TTL is 600 seconds. Each write picks a random TTL from 540 through 660 seconds (inclusive) when jitter is 10%. Jitter spreads expiration across keys and reduces synchronized reloads; it does not prevent concurrent misses for one popular key. In-process request coalescing allows one loader per key per JVM, and waiting callers share its result. Different keys proceed independently; completed futures are removed. This is not distributed coordination across application instances.

Create and update use PostgreSQL transactions. Updates invalidate their cache key after transaction commit. If Redis invalidation fails, the DB update remains successful and the old value can remain visible until its TTL expires. A read already in flight can also repopulate an old value after invalidation; strict cross-instance consistency needs a versioned cache protocol or transactional outbox and is outside this starter. Negative caching and stale-while-revalidate are disabled; stale serving has materially different consistency behavior and should be designed for endpoint requirements before enabling.

## Cache strategies and trade-offs

### 1. Cache-aside (read-through in the service)

1. `ProductService` delegates reads to `CacheAsideService`.
2. The cache service builds a namespaced product key through `CacheKeyFactory`.
3. Redis is checked before PostgreSQL.
4. A valid JSON response is returned immediately.
5. A missing key falls through to the product repository.
6. A database result is converted to the API response DTO.
7. The DTO is serialized as JSON and written to Redis with an explicit TTL.
8. A Redis read error also falls back to PostgreSQL.
9. A Redis write error does not discard a successful database result.
10. PostgreSQL remains authoritative; cache contents can always be rebuilt.

### 2. TTL with per-write jitter

1. Cache entries expire automatically rather than living forever.
2. The default base TTL is 600 seconds.
3. The default jitter is 10 percent of that base.
4. Each cache write gets an independently selected TTL from 540 to 660 seconds.
5. The TTL is supplied directly to the Redis write operation.
6. A positive minimum is maintained so entries cannot receive a zero or negative TTL.
7. Expiration bounds how long an entry can remain after an invalidation problem.
8. Randomized expiry spreads reload work across different keys.
9. Jitter does not stop simultaneous requests for one hot key from missing together.
10. The range is configurable with `CACHE_TTL_SECONDS` and `CACHE_TTL_JITTER_PERCENT`.

### 3. Per-key request coalescing (singleflight)

1. Singleflight is enabled by default for cache misses within one application process.
2. The in-flight map is keyed by the same deterministic Redis cache key.
3. The first caller registers a `CompletableFuture` and loads from PostgreSQL.
4. Later callers for that key join the registered future.
5. Those waiters share the first caller's result or failure.
6. Loads for different keys use independent futures and can proceed concurrently.
7. The entry is removed when loading completes, whether it succeeds or fails.
8. Failed loads therefore do not block future attempts.
9. This reduces duplicate database reads for bursts on one key in one JVM.
10. It does not coordinate separate application instances or replace distributed controls.

### 4. After-commit cache invalidation

1. Product updates are written in a PostgreSQL transaction.
2. The existing Redis key is not deleted before the database commit.
3. A transaction synchronization schedules invalidation after commit.
4. The updated database row remains the durable source of truth.
5. The next request after successful invalidation reloads and caches the new row.
6. Redis invalidation errors are logged and do not reverse a committed database update.
7. If invalidation fails, stale content can remain until its TTL expires.
8. A concurrent older read can still repopulate stale data after invalidation.
9. Multi-instance deployments need durable invalidation, such as a transactional outbox.
10. Create operations do not pre-populate Redis; the first read fills the cache.

### 5. JSON values with versioned keys

1. Cache entries contain serialized `ProductResponse` values.
2. Jackson uses the application's shared mapper.
3. RedisTemplate uses string serializers for keys and values.
4. Java native object serialization is not used.
5. Polymorphic/default typing is not enabled for cache input.
6. Cache keys follow `redis-cache:v2:product:{id}`.
7. The version allows incompatible response formats to use a new namespace.
8. Malformed or incompatible entries are ignored and treated as misses.
9. The database is queried and a fresh JSON entry is written when possible.
10. Changing the version avoids trying to interpret entries from an older DTO shape.

### Strategy trade-offs

| Strategy | Status | Benefits | Costs and limits |
|---|---|---|---|
| Cache-aside | Active | Simple flow; database remains authoritative; Redis outages can degrade to DB reads | Cache misses add DB work; first read has higher latency; stale entries are possible |
| TTL with jitter | Active | Bounds ordinary cache lifetime; spreads expirations across keys | Expired entries reload from DB; jitter does not prevent a same-key stampede |
| Per-key singleflight | Active, per JVM | Coalesces concurrent same-key misses and avoids duplicate loads | No coordination across instances; waiters inherit loader latency and failure |
| After-commit invalidation | Active on update | Avoids deleting cache before a DB transaction commits | Invalidation can fail; in-flight old reads can repopulate; multi-instance delivery is not durable |
| Versioned JSON keys | Active | Human-readable values; safe format evolution through namespace changes | Old namespace entries remain until expiration; DTO changes need deliberate version handling |
| Negative caching | Disabled | Could reduce repeated DB lookups for missing IDs | Can hide newly created records until short TTL/invalidation; absent records are not cached here |
| Stale-while-revalidate | Disabled | Could serve a recent value while refreshing after freshness expiry | More metadata and concurrency complexity; intentionally not used where freshness is required |

## Local setup (macOS)

Requirements: Java 21+, Maven, and Docker Desktop (for the provided local services). Copy `.env.example` to `.env`, set `DB_PASSWORD` to a local development password, and edit other values as needed. `spring-dotenv` loads `.env` before Spring configuration is resolved. Real process environment variables take precedence over `.env`. `application.yml` contains environment-variable references without embedded connection defaults, so missing configuration fails instead of silently connecting with fallback credentials. `.env` is ignored by Git; `.env.example` is a placeholder template, not a credentials file. For deployment, inject credentials through the environment or secret manager.

Start PostgreSQL and Redis:

The compose service initializes only PostgreSQL's maintenance database (`postgres`), not `redis_cache`. Create the application database manually once:

```bash
docker compose exec postgres psql -U postgres -d postgres -c 'CREATE DATABASE redis_cache;'
```

Use the matching `DB_USERNAME` and `POSTGRES_ADMIN_DB` if changed in `.env`. The app database name comes from `DB_NAME` and must be created manually. Then start the application:

```bash
mvn spring-boot:run
```

Local Hibernate `ddl-auto=update` creates/updates tables only after connecting to the already-existing database. Production should use Flyway or Liquibase and set `JPA_DDL_AUTO=validate` or `none`. There is no database initializer and no startup database creation.

Check `http://localhost:8080/actuator/health` and `http://localhost:8080/actuator/metrics/app.cache.hits`. Only health, info, metrics, and Prometheus are exposed; health details are hidden.

## API

```bash
curl -X POST http://localhost:8080/api/products \
  -H 'Content-Type: application/json' \
  -d '{"name":"Keyboard","price":79.90,"category":"Accessories","description":"Mechanical keyboard"}'

curl http://localhost:8080/api/products/1

curl -X PUT http://localhost:8080/api/products/1 \
  -H 'Content-Type: application/json' \
  -d '{"name":"Keyboard v2","price":89.90,"category":"Accessories","description":"Updated mechanical keyboard"}'
```

Names must be nonblank and no longer than 200 characters; prices must be nonnegative; categories must be nonblank and no longer than 80 characters; descriptions are optional and limited to 2,000 characters. Invalid input returns 400, missing products 404, and unexpected errors a generic 500 problem response.

Inspect keys and TTL:

```bash
docker compose exec redis redis-cli KEYS 'redis-cache:*'
docker compose exec redis redis-cli TTL 'redis-cache:v2:product:1'
```

## Tests

Run `mvn clean test` and `mvn package`. Unit tests use Mockito. The integration test uses Testcontainers PostgreSQL and Redis; Docker must be available. Testcontainers marks the integration test skipped when Docker is unavailable. The integration suite currently covers persistence, JSON cache writing, and update invalidation. Further operational coverage (including nonexistent-database startup) should be added before production use.

## HTTP load test

The dependency-free Python script `scripts/load_test.py` drives concurrent API requests and reports request rate, HTTP outcomes, and latency percentiles. Start the application and its PostgreSQL/Redis dependencies first.

```bash
python3 scripts/load_test.py --concurrency 20 --duration 30 --mode warm
python3 scripts/load_test.py --concurrency 10 --duration 15 --mode miss
python3 scripts/load_test.py --concurrency 20 --duration 30 --mode mixed
python3 scripts/load_test.py --concurrency 20 --duration 30 --mode random --pool-size 500
python3 scripts/load_test.py --concurrency 10 --duration 15 --mode write
python3 scripts/load_test.py --product-id 123 --concurrency 50 --duration 60 --mode warm
```

Without `--product-id`, read modes create setup data and use it for the run. `warm` exercises one warmed cache key. `miss` requests generated nonexistent IDs and expects 404 responses, measuring database-backed not-found handling. `mixed` alternates cache hits with not-found requests. `random` creates a product pool (100 by default), warms its cache entries, and reads random keys; use `--pool-size` to change the working set. `write` measures product creation and leaves load records in PostgreSQL. These modes represent different workload shapes; warm reads mainly measure the HTTP/cache-hit path.

### HTTP Load Test Report

#### Summary

These measurements came from `scripts/load_test.py` against the local product API. All reported requests completed with HTTP 200. The results show a useful capacity trend for this particular setup: throughput stayed near 8,000 requests/second at 20 and 100 workers for a warmed key, then fell as concurrency increased sharply. At 1,000 workers, latency rose substantially; at 3,000 workers, throughput declined further and tail latency reached seconds.

Treat the figures as observations from one environment, not as production capacity guarantees. The host hardware, app/JVM settings, Redis/PostgreSQL placement, background load, warm-up duration, and repeatability were not captured alongside the measurements. The Python client itself also contributes overhead, especially at high thread counts.

#### Measurements

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

#### Reading the results

- At 20 workers, the warmed single-key and warmed 100-key workloads were close: 7,981.54 and 8,090.28 req/s. This indicates similar observed throughput for these two cache-hit shapes at that concurrency.
- At 100 workers on one warm key, throughput was 8,110 req/s, close to the 20-worker result, while median latency increased from 2.35 ms to 11.59 ms. Added concurrency did not improve the observed rate and increased waiting time.
- At 1,000 workers, the warm run reached 4,412.38 req/s with p95 latency of 533.69 ms. The separate 1-second random run reached 4,294.17 req/s, with p95 latency of 467.25 ms. That short run is less comparable to 30-second runs and should be repeated for a stable comparison.
- At 3,000 workers, the two random runs reached 1,551.82 and 1,733.23 req/s. Their medians were 124–184 ms, while p95 values were 1.69–2.01 seconds and p99 values were 2.40–3.29 seconds. This indicates substantial queuing or resource contention at this offered concurrency in the measured setup.
- Every request returned 200, so these runs did not show API errors. Successful status codes do not imply acceptable latency; the high-concurrency tail latencies may still be unsuitable for an interactive workload.

The load generator uses a fixed number of synchronous Python threads. It is a closed-loop test: each worker waits for its response before sending another request. Consequently, the offered request rate falls as latency rises. Thread scheduling, socket behavior, application request-thread limits, JVM behavior, Redis, PostgreSQL, and host resources can all contribute. These measurements alone do not identify the bottleneck.

#### Reproduction

Start the application, PostgreSQL, and Redis, then run from the same client environment used for these measurements:

```bash
python3 scripts/load_test.py --concurrency 20 --duration 30 --mode warm
python3 scripts/load_test.py --concurrency 20 --duration 30 --mode random
python3 scripts/load_test.py --concurrency 100 --duration 30 --mode warm
python3 scripts/load_test.py --concurrency 1000 --duration 30 --mode warm
python3 scripts/load_test.py --concurrency 3000 --duration 30 --mode random
```

The random mode creates and warms 100 products by default. Runs create database records and leave them behind. The output includes min, median, p95, p99, max latency, throughput, and status counts.

#### Recommended next measurements

1. Repeat each workload at least three times and compare median throughput and tail latency across runs.
2. Record the machine model, OS, JVM version and options, Spring/Tomcat settings, Redis/PostgreSQL configuration, and whether client and server share a host.
3. Run the load generator on a separate host to reduce client-side CPU and thread contention with the application.
4. Capture CPU, heap/GC pauses, active HTTP threads, Hikari pool usage, Redis command latency, and PostgreSQL activity during each run.
5. Use a pool size larger than the expected hot set when assessing key distribution, and add separate database-miss and write tests for those paths. Do not interpret the warm-cache figures as PostgreSQL capacity.
6. Define an acceptable p95/p99 latency objective before treating any concurrency level as a target.

No production capacity or scaling conclusion should be made from these runs without the missing environment data and repeat measurements.

Recorded example measurements and interpretation are in [docs/load-test-report.md](docs/load-test-report.md).

## Metrics and limitations

Micrometer counters use bounded names for hits, misses, Redis errors, DB fallback reads, singleflight loader executions/waiters, and durations; product IDs are not labels. Redis timeouts default to two seconds. No retry loop is used, avoiding outage amplification. Pool and thread behavior are bounded by Hikari and the request executor.

The example does not promise distributed stampede protection or strict cache consistency. For multiple instances, use durable invalidation such as a transactional outbox and define acceptable staleness. Production hardening should also add schema migrations, authentication/rate limits appropriate to the deployment, deployment-specific secret management, and operational alerts.

## Troubleshooting

- `Connection refused` on startup usually means PostgreSQL or Redis is not healthy; check `docker compose ps` and service logs.
- `database "redis_cache" does not exist` means the one-time manual `CREATE DATABASE` command has not been run.
- Check that `.env` contains the required variables from `.env.example` and that process environment values are not overriding them.
- Maven dependency resolution requires access to Maven Central (or a configured mirror).
- Product cache keys use the `v2` namespace after adding category and description fields; any existing `v1` entries are ignored and expire under their prior TTL.
