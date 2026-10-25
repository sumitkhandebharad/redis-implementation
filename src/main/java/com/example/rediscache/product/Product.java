package com.example.rediscache.product;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "products")
public class Product {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal price;
    @Column(length = 80)
    private String category;
    @Column(length = 2000)
    private String description;
    protected Product() { }
    public Product(String name, BigDecimal price, String category, String description) {
        this.name = name; this.price = price; this.category = category; this.description = description;
    }
    public Long getId() { return id; }
    public String getName() { return name; }
    public BigDecimal getPrice() { return price; }
    public String getCategory() { return category; }
    public String getDescription() { return description; }
    public void update(String name, BigDecimal price, String category, String description) {
        this.name = name; this.price = price; this.category = category; this.description = description;
    }
}
