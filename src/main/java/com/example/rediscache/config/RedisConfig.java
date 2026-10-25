package com.example.rediscache.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        var template = new RedisTemplate<String, String>();
        var strings = new StringRedisSerializer();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(strings);
        template.setValueSerializer(strings);
        template.setHashKeySerializer(strings);
        template.setHashValueSerializer(strings);
        template.afterPropertiesSet();
        return template;
    }
}
