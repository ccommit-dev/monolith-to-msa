package com.ccommit.monolith_to_msa.service.order;

import com.ccommit.monolith_to_msa.domain.order.Order;
import com.ccommit.monolith_to_msa.domain.order.OrderStatus;
import com.ccommit.monolith_to_msa.domain.product.Product;
import com.ccommit.monolith_to_msa.dto.order.OrderCreateRequest;
import com.ccommit.monolith_to_msa.dto.order.OrderResponse;
import com.ccommit.monolith_to_msa.exception.InsufficientStockException;
import com.ccommit.monolith_to_msa.exception.ProductNotFoundException;
import com.ccommit.monolith_to_msa.repository.order.OrderRepository;
import com.ccommit.monolith_to_msa.repository.product.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * OrderService 단위 테스트
 * Given-When-Then 패턴 사용
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private OrderServiceImpl orderService;

    @Test
    @DisplayName("주문 생성 성공 - 재고 차감 및 주문 생성")
    void createOrder_Success() {
        // Given: 테스트 데이터 준비
        String productId = "product-001";
        Integer quantity = 2;
        Long totalPrice = 20000L;
        
        Product product = Product.builder()
                .productId(productId)
                .name("테스트 상품")
                .price(10000L)
                .stock(10)
                .build();

        OrderCreateRequest request = new OrderCreateRequest();
        request.setCustomerId("customer-001");
        request.setProductId(productId);
        request.setQuantity(quantity);
        request.setTotalPrice(totalPrice);

        Order savedOrder = Order.builder()
                .customerId("customer-001")
                .productId(productId)
                .quantity(quantity)
                .totalPrice(totalPrice)
                .status(OrderStatus.PENDING)
                .build();

        // Mock 설정
        when(productRepository.findByProductIdWithLock(productId))
                .thenReturn(Optional.of(product));
        when(orderRepository.save(any(Order.class)))
                .thenReturn(savedOrder);

        // When: 주문 생성 실행
        OrderResponse response = orderService.createOrder(request);

        // Then: 결과 검증
        assertThat(response).isNotNull();
        assertThat(response.getProductId()).isEqualTo(productId);
        assertThat(response.getQuantity()).isEqualTo(quantity);
        assertThat(response.getStatus()).isEqualTo(OrderStatus.PENDING);
        
        // 재고 차감 확인
        assertThat(product.getStock()).isEqualTo(8); // 10 - 2 = 8
        
        // 메서드 호출 확인
        verify(productRepository, times(1)).findByProductIdWithLock(productId);
        verify(orderRepository, times(1)).save(any(Order.class));
    }

    @Test
    @DisplayName("주문 생성 실패 - 상품을 찾을 수 없음")
    void createOrder_ProductNotFound() {
        // Given
        String productId = "product-999";
        OrderCreateRequest request = new OrderCreateRequest();
        request.setCustomerId("customer-001");
        request.setProductId(productId);
        request.setQuantity(1);
        request.setTotalPrice(10000L);

        when(productRepository.findByProductIdWithLock(productId))
                .thenReturn(Optional.empty());

        // When & Then: 예외 발생 확인
        assertThatThrownBy(() -> orderService.createOrder(request))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining("상품을 찾을 수 없습니다");

        verify(productRepository, times(1)).findByProductIdWithLock(productId);
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("주문 생성 실패 - 재고 부족")
    void createOrder_InsufficientStock() {
        // Given
        String productId = "product-001";
        Integer quantity = 10;
        
        Product product = Product.builder()
                .productId(productId)
                .name("테스트 상품")
                .price(10000L)
                .stock(5) // 재고 부족
                .build();

        OrderCreateRequest request = new OrderCreateRequest();
        request.setCustomerId("customer-001");
        request.setProductId(productId);
        request.setQuantity(quantity);
        request.setTotalPrice(100000L);

        when(productRepository.findByProductIdWithLock(productId))
                .thenReturn(Optional.of(product));

        // When & Then: 예외 발생 확인
        assertThatThrownBy(() -> orderService.createOrder(request))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("재고가 부족합니다");

        verify(productRepository, times(1)).findByProductIdWithLock(productId);
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("주문 생성 - 트랜잭션 롤백 테스트")
    void createOrder_TransactionRollback() {
        // Given
        String productId = "product-001";
        Product product = Product.builder()
                .productId(productId)
                .name("테스트 상품")
                .price(10000L)
                .stock(10)
                .build();

        OrderCreateRequest request = new OrderCreateRequest();
        request.setCustomerId("customer-001");
        request.setProductId(productId);
        request.setQuantity(2);
        request.setTotalPrice(20000L);

        when(productRepository.findByProductIdWithLock(productId))
                .thenReturn(Optional.of(product));
        when(orderRepository.save(any(Order.class)))
                .thenThrow(new RuntimeException("DB 오류"));

        // When & Then: 예외 발생 시 트랜잭션 롤백
        assertThatThrownBy(() -> orderService.createOrder(request))
                .isInstanceOf(RuntimeException.class);

        // 재고 차감이 롤백되어야 함 (실제로는 @Transactional이 처리)
        verify(productRepository, times(1)).findByProductIdWithLock(productId);
        verify(orderRepository, times(1)).save(any(Order.class));
    }
}

