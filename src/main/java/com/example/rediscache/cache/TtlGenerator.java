package com.example.rediscache.cache;

import com.example.rediscache.config.CacheProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class TtlGenerator {
    private final CacheProperties properties;
    public TtlGenerator(CacheProperties properties) { this.properties = properties; }
    public Duration next() { return next(ThreadLocalRandom.current().nextDouble()); }
    Duration next(double sample) {
        long base = properties.ttlSeconds();
        long spread = Math.max(0, Math.round(base * properties.ttlJitterPercent() / 100.0));
        long min = Math.max(1, base - spread), max = Math.max(min, base + spread);
        long ttl = min + Math.min(max - min, (long) (Math.max(0, Math.min(Math.nextDown(1.0), sample)) * (max - min + 1)));
        return Duration.ofSeconds(ttl);
    }
}
