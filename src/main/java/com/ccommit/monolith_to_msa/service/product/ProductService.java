package com.ccommit.monolith_to_msa.service.product;

import com.ccommit.monolith_to_msa.domain.product.Product;

import java.util.Optional;

public interface ProductService {
    
    Optional<Product> getProductByProductId(String productId);
    
    Integer getStockByProductId(String productId);
    
    void evictProductCache(String productId);
    
    void evictStockCache(String productId);
}

