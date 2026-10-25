package com.example.rediscache.product;

import com.example.rediscache.cache.CacheAsideService;
import com.example.rediscache.cache.CacheKeyFactory;
import com.example.rediscache.exception.ProductNotFoundException;
import com.example.rediscache.product.dto.CreateProductRequest;
import com.example.rediscache.product.dto.ProductResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ProductService {
    private static final Logger log = LoggerFactory.getLogger(ProductService.class);
    private final ProductRepository repository;
    private final CacheAsideService cache;
    private final RedisTemplate<String, String> redis;
    private final CacheKeyFactory keys;
    public ProductService(ProductRepository repository, CacheAsideService cache, @Qualifier("redisTemplate") RedisTemplate<String, String> redis, CacheKeyFactory keys) {
        this.repository = repository; this.cache = cache; this.redis = redis; this.keys = keys;
    }
    public ProductResponse get(long id) { return cache.get(id); }
    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        Product saved = repository.save(new Product(request.name().trim(), request.price(), request.category().trim(), cleanDescription(request.description())));
        return response(saved);
    }
    @Transactional
    public ProductResponse update(long id, CreateProductRequest request) {
        Product product = repository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
        product.update(request.name().trim(), request.price(), request.category().trim(), cleanDescription(request.description()));
        ProductResponse response = response(product);
        invalidateAfterCommit(id);
        return response;
    }
    private String cleanDescription(String description) { return description == null || description.isBlank() ? null : description.trim(); }
    private ProductResponse response(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getPrice(), product.getCategory(), product.getDescription());
    }
    private void invalidateAfterCommit(long id) {
        Runnable invalidation = () -> {
            try {
                redis.delete(keys.product(id));
            } catch (DataAccessException e) {
                log.error("Redis invalidation failed after product {} update; stale data may remain until TTL", id, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { invalidation.run(); }
            });
        } else invalidation.run();
    }
}
