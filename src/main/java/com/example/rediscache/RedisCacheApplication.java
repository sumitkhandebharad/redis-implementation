package com.example.rediscache;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RedisCacheApplication {
    public static void main(String[] args) { SpringApplication.run(RedisCacheApplication.class, args); }
}
