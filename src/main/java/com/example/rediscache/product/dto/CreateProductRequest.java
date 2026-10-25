package com.example.rediscache.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateProductRequest(@NotBlank @Size(max = 200) String name,
                                   @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal price,
                                   @NotBlank @Size(max = 80) String category,
                                   @Size(max = 2000) String description) { }
