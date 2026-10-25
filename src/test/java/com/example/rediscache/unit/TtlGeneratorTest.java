package com.example.rediscache.unit;

import com.example.rediscache.cache.TtlGenerator;
import com.example.rediscache.config.CacheProperties;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class TtlGeneratorTest {
    @Test void jitterBoundsAreInclusiveAndPositive() {
        var generator = new TtlGenerator(new CacheProperties(600, 10, true, 30, 300, false));
        assertEquals(Duration.ofSeconds(540), generator.next(0));
        assertEquals(Duration.ofSeconds(660), generator.next(1));
        assertTrue(generator.next().compareTo(Duration.ZERO) > 0);
    }
    @Test void keySchemeIsVersionedAndConsistent() {
        var keys = new com.example.rediscache.cache.CacheKeyFactory();
        assertEquals("redis-cache:v2:product:42", keys.product(42));
    }
}
