package com.example.rediscache.cache;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class CacheMetrics {
    private final MeterRegistry registry;
    public CacheMetrics(MeterRegistry registry) { this.registry = registry; }
    public void increment(String name) { registry.counter("app.cache." + name).increment(); }
    public Timer.Sample startLoad() { return Timer.start(registry); }
    public void stopLoad(Timer.Sample sample) { sample.stop(registry.timer("app.cache.load.duration")); }
}
