package com.ccommit.monolith_to_msa.controller.order;

import com.ccommit.monolith_to_msa.domain.order.OrderStatus;
import com.ccommit.monolith_to_msa.dto.order.OrderCreateRequest;
import com.ccommit.monolith_to_msa.dto.order.OrderResponse;
import com.ccommit.monolith_to_msa.exception.GlobalExceptionHandler;
import com.ccommit.monolith_to_msa.exception.InsufficientStockException;
import com.ccommit.monolith_to_msa.exception.ProductNotFoundException;
import com.ccommit.monolith_to_msa.service.order.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * OrderController 단위 테스트
 * Given-When-Then 패턴 사용
 * Mockito를 사용한 Mock 객체 주입
 */
@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock
    private OrderService orderService;

    @InjectMocks
    private OrderController orderController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // MockMvc 설정 (GlobalExceptionHandler 포함)
        mockMvc = MockMvcBuilders.standaloneSetup(orderController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("주문 생성 성공 - 201 Created")
    void createOrder_Success() throws Exception {
        // Given
        OrderCreateRequest request = new OrderCreateRequest();
        request.setCustomerId("customer-001");
        request.setProductId("product-001");
        request.setQuantity(2);
        request.setTotalPrice(20000L);

        OrderResponse response = OrderResponse.builder()
                .id(1L)
                .customerId("customer-001")
                .productId("product-001")
                .quantity(2)
                .totalPrice(20000L)
                .status(OrderStatus.PENDING)
                .build();

        when(orderService.createOrder(any(OrderCreateRequest.class)))
                .thenReturn(response);

        // When & Then
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "customerId": "customer-001",
                                "productId": "product-001",
                                "quantity": 2,
                                "totalPrice": 20000,
                                "paymentMethod": "CREDIT_CARD"
                            }
                            """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.customerId").value("customer-001"))
                .andExpect(jsonPath("$.productId").value("product-001"))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.status").value("PENDING"));
        
        // Mockito verify: OrderService.createOrder가 한 번 호출되었는지 확인
        verify(orderService, times(1)).createOrder(any(OrderCreateRequest.class));
    }

    @Test
    @DisplayName("주문 생성 실패 - 입력 검증 오류 (400 Bad Request)")
    void createOrder_ValidationError() throws Exception {
        // Given: 필수 필드 누락 (빈 JSON)
        // When & Then
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Error"));
    }

    @Test
    @DisplayName("주문 생성 실패 - 상품을 찾을 수 없음 (404 Not Found)")
    void createOrder_ProductNotFound() throws Exception {
        // Given
        OrderCreateRequest request = new OrderCreateRequest();
        request.setCustomerId("customer-001");
        request.setProductId("product-999");
        request.setQuantity(1);
        request.setTotalPrice(10000L);

        when(orderService.createOrder(any(OrderCreateRequest.class)))
                .thenThrow(new ProductNotFoundException("product-999"));

        // When & Then
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "customerId": "customer-001",
                                "productId": "product-999",
                                "quantity": 1,
                                "totalPrice": 10000,
                                "paymentMethod": "CREDIT_CARD"
                            }
                            """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Product Not Found"))
                .andExpect(jsonPath("$.message").value("상품을 찾을 수 없습니다: product-999"));
        
        // Mockito verify: OrderService.createOrder가 한 번 호출되었는지 확인
        verify(orderService, times(1)).createOrder(any(OrderCreateRequest.class));
    }

    @Test
    @DisplayName("주문 생성 실패 - 재고 부족 (400 Bad Request)")
    void createOrder_InsufficientStock() throws Exception {
        // Given
        OrderCreateRequest request = new OrderCreateRequest();
        request.setCustomerId("customer-001");
        request.setProductId("product-001");
        request.setQuantity(10);
        request.setTotalPrice(100000L);

        when(orderService.createOrder(any(OrderCreateRequest.class)))
                .thenThrow(new InsufficientStockException("product-001", 10, 5));

        // When & Then
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "customerId": "customer-001",
                                "productId": "product-001",
                                "quantity": 10,
                                "totalPrice": 100000,
                                "paymentMethod": "CREDIT_CARD"
                            }
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Insufficient Stock"))
                .andExpect(jsonPath("$.message").value("재고가 부족합니다. 상품: product-001, 요청 수량: 10, 현재 재고: 5"));
        
        // Mockito verify: OrderService.createOrder가 한 번 호출되었는지 확인
        verify(orderService, times(1)).createOrder(any(OrderCreateRequest.class));
    }
}

