package com.example.rediscache.cache;

import org.springframework.stereotype.Component;

@Component
public class CacheKeyFactory {
    public String product(long id) { return "redis-cache:v2:product:" + id; }
}
