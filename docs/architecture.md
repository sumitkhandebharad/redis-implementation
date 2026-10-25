# Architecture

```mermaid
flowchart LR
  Client --> Controller[ProductController]
  Controller --> Service[ProductService]
  Service --> Aside[CacheAsideService]
  Aside -->|RedisTemplate string JSON| Redis[(Redis)]
  Aside -->|miss/failure| Repo[ProductRepository]
  Repo --> DB[(PostgreSQL source of truth)]
  Service -->|after transaction commit| Redis
  Aside --> Metrics[Micrometer]
```

## Cache hit

```mermaid
sequenceDiagram
  participant C as Client
  participant A as CacheAsideService
  participant R as Redis
  C->>A: GET product id
  A->>R: GET versioned key
  R-->>A: JSON DTO
  A-->>C: Product response
```

## Cache miss

```mermaid
sequenceDiagram
  participant C as Client
  participant A as CacheAsideService
  participant R as Redis
  participant D as PostgreSQL
  C->>A: GET product id
  A->>R: GET versioned key
  R-->>A: absent / invalid / unavailable
  A->>D: findById
  D-->>A: canonical product
  A->>R: SET JSON with jittered TTL (best effort)
  A-->>C: Product response
```

## Concurrent misses

```mermaid
sequenceDiagram
  participant C1 as Caller 1
  participant C2 as Caller 2
  participant A as JVM singleflight map
  participant D as PostgreSQL
  C1->>A: miss, register future for key
  C2->>A: same miss, join existing future
  A->>D: one findById
  D-->>A: product result
  A-->>C1: complete result
  A-->>C2: same result
```

The map coordinates callers only inside one JVM. A failure completes waiters exceptionally and removes the in-flight entry so a later request can retry. Update invalidation runs after the database commit; failed invalidation can leave stale data until TTL expiry.
