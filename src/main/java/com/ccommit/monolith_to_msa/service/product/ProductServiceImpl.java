package com.ccommit.monolith_to_msa.service.product;

import com.ccommit.monolith_to_msa.domain.product.Product;
import com.ccommit.monolith_to_msa.repository.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Product 서비스 구현체
 * Cache Aside 패턴 적용
 * @Cacheable: 캐시에서 조회, 없으면 DB 조회 후 캐시 저장
 * @CacheEvict: 캐시 무효화 (재고 변경 시)
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ProductServiceImpl implements ProductService {
    
    private final ProductRepository productRepository;
    
    /**
     * 상품 조회 (캐시 적용)
     * Cache Aside 패턴:
     * 1. 캐시에서 조회 시도
     * 2. 캐시 미스 시 DB 조회
     * 3. DB 결과를 캐시에 저장
     * 
     * TTL: 60초 (product 캐시 설정)
     */
    @Override
    @Cacheable(value = "product", key = "#productId", unless = "#result == null")
    public Optional<Product> getProductByProductId(String productId) {
        log.info("DB에서 상품 조회: productId={}", productId);
        return productRepository.findByProductId(productId);
    }
    
    /**
     * 재고 조회 (캐시 적용)
     * Cache Aside 패턴 적용
     * 
     * TTL: 300초 (stock 캐시 설정)
     */
    @Override
    @Cacheable(value = "stock", key = "#productId", unless = "#result == null")
    public Integer getStockByProductId(String productId) {
        log.info("DB에서 재고 조회: productId={}", productId);
        return productRepository.findByProductId(productId)
                .map(Product::getStock)
                .orElse(null);
    }
    
    /**
     * 상품 캐시 무효화
     * 재고 변경 시 호출
     */
    @Override
    @CacheEvict(value = "product", key = "#productId")
    public void evictProductCache(String productId) {
        log.info("상품 캐시 무효화: productId={}", productId);
    }
    
    /**
     * 재고 캐시 무효화
     * 재고 변경 시 호출
     */
    @Override
    @CacheEvict(value = "stock", key = "#productId")
    public void evictStockCache(String productId) {
        log.info("재고 캐시 무효화: productId={}", productId);
    }
}

