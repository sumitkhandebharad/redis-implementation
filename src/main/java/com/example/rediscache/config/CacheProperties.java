package com.example.rediscache.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.cache")
public record CacheProperties(@Min(1) long ttlSeconds, @Min(0) @Max(100) int ttlJitterPercent,
                              boolean singleflightEnabled, @Min(1) long negativeTtlSeconds,
                              @Min(0) long staleRetentionSeconds, boolean backgroundRefreshEnabled) { }
