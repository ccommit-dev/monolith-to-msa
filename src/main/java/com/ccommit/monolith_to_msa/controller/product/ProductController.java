package com.ccommit.monolith_to_msa.controller.product;

import com.ccommit.monolith_to_msa.domain.product.Product;
import com.ccommit.monolith_to_msa.service.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {
    
    private final ProductService productService;
    
    @GetMapping("/{productId}")
    public ResponseEntity<Product> getProduct(@PathVariable String productId) {
        Optional<Product> product = productService.getProductByProductId(productId);
        return product.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    @GetMapping("/{productId}/stock")
    public ResponseEntity<Integer> getStock(@PathVariable String productId) {
        Integer stock = productService.getStockByProductId(productId);
        if (stock == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(stock);
    }
    
    @DeleteMapping("/{productId}/cache")
    public ResponseEntity<Void> evictCache(@PathVariable String productId) {
        productService.evictProductCache(productId);
        productService.evictStockCache(productId);
        return ResponseEntity.noContent().build();
    }
}

