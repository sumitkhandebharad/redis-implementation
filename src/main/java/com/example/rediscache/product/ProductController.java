package com.example.rediscache.product;

import com.example.rediscache.product.dto.CreateProductRequest;
import com.example.rediscache.product.dto.ProductResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
@Validated
public class ProductController {
    private final ProductService service;
    public ProductController(ProductService service) { this.service = service; }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(@Valid @RequestBody CreateProductRequest request) { return service.create(request); }
    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable @Positive long id) { return service.get(id); }
    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable @Positive long id, @Valid @RequestBody CreateProductRequest request) { return service.update(id, request); }
}
