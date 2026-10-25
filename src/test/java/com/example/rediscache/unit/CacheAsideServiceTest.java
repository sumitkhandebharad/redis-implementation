package com.example.rediscache.unit;

import com.example.rediscache.cache.*;
import com.example.rediscache.config.CacheProperties;
import com.example.rediscache.exception.ProductNotFoundException;
import com.example.rediscache.product.Product;
import com.example.rediscache.product.ProductRepository;
import com.example.rediscache.product.dto.ProductResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CacheAsideServiceTest {
    RedisTemplate<String, String> redis;
    ValueOperations<String, String> values;
    ProductRepository repository;
    CacheAsideService service;
    CacheKeyFactory keys = new CacheKeyFactory();

    @SuppressWarnings("unchecked")
    @BeforeEach void setup() {
        redis = mock(RedisTemplate.class); values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        repository = mock(ProductRepository.class);
        var properties = new CacheProperties(100, 10, true, 30, 300, false);
        service = new CacheAsideService(redis, new ObjectMapper(), repository, keys,
                new TtlGenerator(properties), properties, new CacheMetrics(new SimpleMeterRegistry()));
    }
    @Test void cacheHitAvoidsDatabase() throws Exception {
        when(values.get(keys.product(1))).thenReturn("{\"id\":1,\"name\":\"cached\",\"price\":1.00}");
        assertEquals("cached", service.get(1).name());
        verifyNoInteractions(repository);
    }
    @Test void cacheMissReadsDatabaseAndWritesJsonWithTtl() {
        when(repository.findById(1L)).thenReturn(Optional.of(product(1)));
        ProductResponse response = service.get(1);
        assertEquals("product", response.name());
        verify(values).set(eq(keys.product(1)), contains("\"name\":\"product\""), any(Duration.class));
    }
    @Test void malformedEntryIsRecoveredFromDatabase() {
        when(values.get(keys.product(1))).thenReturn("not-json");
        when(repository.findById(1L)).thenReturn(Optional.of(product(1)));
        assertEquals("product", service.get(1).name());
    }
    @Test void redisReadAndWriteFailuresDoNotFailDatabaseRead() {
        when(values.get(keys.product(1))).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
        when(repository.findById(1L)).thenReturn(Optional.of(product(1)));
        doThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        assertEquals("product", service.get(1).name());
    }
    @Test void missingProductIsNotCached() {
        when(repository.findById(9L)).thenReturn(Optional.empty());
        assertThrows(ProductNotFoundException.class, () -> service.get(9));
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }
    @Test void concurrentSameKeyMissesShareOneLoad() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(values.get(keys.product(1))).thenReturn(null);
        when(repository.findById(1L)).thenAnswer(inv -> { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return Optional.of(product(1)); });
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            var first = pool.submit(() -> service.get(1)); assertTrue(entered.await(2, TimeUnit.SECONDS));
            var rest = new Future<?>[7]; for (int i=0;i<rest.length;i++) rest[i] = pool.submit(() -> service.get(1));
            release.countDown(); first.get(3, TimeUnit.SECONDS); for (var f: rest) f.get(3, TimeUnit.SECONDS);
            verify(repository, times(1)).findById(1L);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    private Product product(long id) {
        Product p = new Product("product", new BigDecimal("12.50"), "test", "test product");
        try { var f = Product.class.getDeclaredField("id"); f.setAccessible(true); f.set(p, id); } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        return p;
    }
}
