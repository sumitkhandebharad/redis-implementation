package com.example.rediscache.cache;

import com.example.rediscache.config.CacheProperties;
import com.example.rediscache.exception.ProductNotFoundException;
import com.example.rediscache.product.ProductRepository;
import com.example.rediscache.product.dto.ProductResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CacheAsideService {
    private static final Logger log = LoggerFactory.getLogger(CacheAsideService.class);
    private final RedisTemplate<String, String> redis;
    private final ObjectMapper mapper;
    private final ProductRepository repository;
    private final CacheKeyFactory keys;
    private final TtlGenerator ttl;
    private final CacheProperties properties;
    private final CacheMetrics metrics;
    private final ConcurrentHashMap<String, CompletableFuture<ProductResponse>> flights = new ConcurrentHashMap<>();

    public CacheAsideService(@Qualifier("redisTemplate") RedisTemplate<String, String> redis, ObjectMapper mapper, ProductRepository repository,
                             CacheKeyFactory keys, TtlGenerator ttl, CacheProperties properties, CacheMetrics metrics) {
        this.redis = redis; this.mapper = mapper; this.repository = repository; this.keys = keys;
        this.ttl = ttl; this.properties = properties; this.metrics = metrics;
    }

    public ProductResponse get(long id) {
        String key = keys.product(id);
        try {
            String json = redis.opsForValue().get(key);
            if (json != null) {
                try {
                    ProductResponse cached = mapper.readValue(json, ProductResponse.class);
                    if (cached.id() != null && cached.id() == id) {
                        metrics.increment("hits");
                        return cached;
                    }
                    log.warn("Ignoring incompatible cache entry for product {}", id);
                } catch (JsonProcessingException e) {
                    log.warn("Ignoring malformed cache entry for product {}", id);
                }
            }
            metrics.increment("misses");
        } catch (DataAccessException e) {
            metrics.increment("read.errors");
            log.warn("Redis read failed for product {}; falling back to PostgreSQL", id, e);
        }
        if (!properties.singleflightEnabled()) return load(id, key);
        CompletableFuture<ProductResponse> mine = new CompletableFuture<>();
        CompletableFuture<ProductResponse> active = flights.putIfAbsent(key, mine);
        if (active != null) {
            metrics.increment("singleflight.waiters");
            try { return active.join(); }
            catch (CompletionException e) { throw propagate(e.getCause()); }
        }
        try {
            metrics.increment("singleflight.loader");
            ProductResponse result = load(id, key);
            mine.complete(result);
            return result;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            flights.remove(key, mine);
        }
    }

    private ProductResponse load(long id, String key) {
        Timer.Sample sample = metrics.startLoad();
        try {
            metrics.increment("database.fallback.reads");
            var product = repository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
            ProductResponse response = new ProductResponse(product.getId(), product.getName(), product.getPrice(), product.getCategory(), product.getDescription());
            try {
                redis.opsForValue().set(key, mapper.writeValueAsString(response), ttl.next());
            } catch (DataAccessException e) {
                metrics.increment("write.errors");
                log.warn("Redis write failed for product {}; returning database result", id, e);
            } catch (JsonProcessingException e) {
                metrics.increment("write.errors");
                log.error("Could not serialize product {} for cache", id, e);
            }
            return response;
        } finally { metrics.stopLoad(sample); }
    }

    private RuntimeException propagate(Throwable cause) {
        if (cause instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException("Product load failed", cause);
    }
}
