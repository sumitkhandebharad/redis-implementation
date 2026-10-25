package com.example.rediscache.integration;

import com.example.rediscache.product.ProductService;
import com.example.rediscache.product.dto.CreateProductRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ProductIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("redis_cache").withUsername("test").withPassword("test");
    @Container static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl); registry.add("spring.datasource.username", postgres::getUsername); registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost); registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }
    @Autowired ProductService service;
    @Autowired RedisTemplate<String, String> redisTemplate;
    @Test void persistsAndCachesCanonicalJson() {
        var created = service.create(new CreateProductRequest("integration", new BigDecimal("5.25"), "test", "integration fixture"));
        assertEquals(created, service.get(created.id()));
        assertTrue(redisTemplate.opsForValue().get("redis-cache:v2:product:" + created.id()).contains("integration"));
        service.update(created.id(), new CreateProductRequest("updated", new BigDecimal("6.25"), "updated-category", null));
        assertEquals("updated", service.get(created.id()).name());
    }
}
