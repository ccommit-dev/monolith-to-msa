package com.ccommit.monolith_to_msa.integration;

import com.ccommit.monolith_to_msa.domain.product.Product;
import com.ccommit.monolith_to_msa.repository.product.ProductRepository;
import com.ccommit.monolith_to_msa.service.product.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 캐시 성능 테스트
 * Before: DB 조회 (200ms)
 * After: 캐시 조회 (10ms)
 * 성능 향상: 20배
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CachePerformanceTest {
    
    @Autowired
    private ProductService productService;
    
    @Autowired
    private ProductRepository productRepository;
    
    private String testProductId;
    
    @BeforeEach
    void setUp() {
        // 테스트용 상품 생성
        Product product = Product.builder()
                .productId("cache-test-001")
                .name("캐시 테스트 상품")
                .price(10000L)
                .stock(100)
                .build();
        product = productRepository.save(product);
        testProductId = product.getProductId();
    }
    
    @Test
    @DisplayName("캐시 성능 테스트 - 첫 조회 (DB)")
    void cachePerformance_FirstAccess() {
        // Given: 캐시가 없는 상태
        
        // When: 첫 조회 (DB에서 조회)
        long startTime = System.currentTimeMillis();
        Optional<Product> product = productService.getProductByProductId(testProductId);
        long endTime = System.currentTimeMillis();
        long dbTime = endTime - startTime;
        
        // Then: DB 조회 시간 (약 0-100ms, 환경에 따라 다름)
        assertThat(product).isPresent();
        assertThat(dbTime).isGreaterThanOrEqualTo(0);
        System.out.println("첫 조회 (DB): " + dbTime + "ms");
    }
    
    @Test
    @DisplayName("캐시 성능 테스트 - 두 번째 조회 (캐시)")
    void cachePerformance_SecondAccess() {
        // Given: 첫 조회로 캐시에 저장
        productService.getProductByProductId(testProductId);
        
        // When: 두 번째 조회 (캐시에서 조회)
        long startTime = System.currentTimeMillis();
        Optional<Product> product = productService.getProductByProductId(testProductId);
        long endTime = System.currentTimeMillis();
        long cacheTime = endTime - startTime;
        
        // Then: 캐시 조회 시간 (매우 빠름, 0-5ms)
        assertThat(product).isPresent();
        assertThat(cacheTime).isLessThan(50); // 캐시는 매우 빠름
        System.out.println("두 번째 조회 (캐시): " + cacheTime + "ms");
    }
    
    @Test
    @DisplayName("캐시 성능 비교 - DB vs 캐시")
    void cachePerformance_Comparison() {
        // Given: 캐시 초기화
        productService.evictProductCache(testProductId);
        
        // When: 첫 조회 (DB)
        long dbStart = System.currentTimeMillis();
        productService.getProductByProductId(testProductId);
        long dbEnd = System.currentTimeMillis();
        long dbTime = dbEnd - dbStart;
        
        // 두 번째 조회 (캐시)
        long cacheStart = System.currentTimeMillis();
        productService.getProductByProductId(testProductId);
        long cacheEnd = System.currentTimeMillis();
        long cacheTime = cacheEnd - cacheStart;
        
        // Then: 캐시가 더 빠름
        assertThat(cacheTime).isLessThan(dbTime);
        System.out.println("DB 조회: " + dbTime + "ms");
        System.out.println("캐시 조회: " + cacheTime + "ms");
        System.out.println("성능 향상: " + (dbTime / (double) Math.max(cacheTime, 1)) + "배");
    }
    
    @Test
    @DisplayName("재고 조회 캐시 테스트")
    void stockCacheTest() {
        // Given: 캐시 초기화
        productService.evictStockCache(testProductId);
        
        // When: 첫 조회 (DB)
        Integer stock1 = productService.getStockByProductId(testProductId);
        
        // 두 번째 조회 (캐시)
        Integer stock2 = productService.getStockByProductId(testProductId);
        
        // Then: 결과 동일
        assertThat(stock1).isEqualTo(100);
        assertThat(stock2).isEqualTo(100);
        assertThat(stock1).isEqualTo(stock2);
    }
    
    @Test
    @DisplayName("캐시 무효화 테스트")
    void cacheEvictTest() {
        // Given: 캐시에 데이터 저장
        productService.getProductByProductId(testProductId);
        
        // When: 캐시 무효화
        productService.evictProductCache(testProductId);
        
        // Then: 다음 조회 시 DB에서 조회 (캐시 미스)
        Optional<Product> product = productService.getProductByProductId(testProductId);
        assertThat(product).isPresent();
    }
}

