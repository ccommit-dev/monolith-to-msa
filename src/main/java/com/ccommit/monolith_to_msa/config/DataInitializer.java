package com.ccommit.monolith_to_msa.config;

import com.ccommit.monolith_to_msa.domain.product.Product;
import com.ccommit.monolith_to_msa.repository.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final ProductRepository productRepository;

    @Override
    public void run(String... args) throws Exception {
        if (productRepository.count() == 0) {
            log.info("테스트 상품 데이터 초기화 시작");

            Product product1 = Product.builder()
                    .productId("product-001")
                    .name("노트북")
                    .price(1500000L)
                    .stock(100)
                    .build();

            Product product2 = Product.builder()
                    .productId("product-002")
                    .name("마우스")
                    .price(30000L)
                    .stock(200)
                    .build();

            Product product3 = Product.builder()
                    .productId("product-003")
                    .name("키보드")
                    .price(80000L)
                    .stock(150)
                    .build();

            productRepository.save(product1);
            productRepository.save(product2);
            productRepository.save(product3);

            log.info("테스트 상품 데이터 초기화 완료: 3개");
        }
    }
}