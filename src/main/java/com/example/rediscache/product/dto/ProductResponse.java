package com.example.rediscache.product.dto;

import java.math.BigDecimal;

public record ProductResponse(Long id, String name, BigDecimal price, String category, String description) { }
