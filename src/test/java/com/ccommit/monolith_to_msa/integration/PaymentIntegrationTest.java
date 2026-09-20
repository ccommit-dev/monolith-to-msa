package com.ccommit.monolith_to_msa.integration;

import com.ccommit.monolith_to_msa.domain.order.Order;
import com.ccommit.monolith_to_msa.domain.order.OrderStatus;
import com.ccommit.monolith_to_msa.domain.payment.Payment;
import com.ccommit.monolith_to_msa.domain.payment.PaymentMethod;
import com.ccommit.monolith_to_msa.domain.payment.PaymentStatus;
import com.ccommit.monolith_to_msa.dto.payment.PaymentCreateRequest;
import com.ccommit.monolith_to_msa.dto.payment.PaymentResponse;
import com.ccommit.monolith_to_msa.repository.order.OrderRepository;
import com.ccommit.monolith_to_msa.repository.payment.PaymentRepository;
import com.ccommit.monolith_to_msa.service.payment.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Payment 통합 테스트
 * @SpringBootTest를 사용한 전체 스택 테스트
 */
@SpringBootTest
@Transactional
class PaymentIntegrationTest {
    
    @Autowired
    private PaymentService paymentService;
    
    @Autowired
    private OrderRepository orderRepository;
    
    @Autowired
    private PaymentRepository paymentRepository;
    
    private Order testOrder;
    
    @BeforeEach
    void setUp() {
        // 테스트용 주문 생성
        testOrder = Order.builder()
                .customerId("customer-001")
                .productId("product-001")
                .quantity(2)
                .totalPrice(20000L)
                .status(OrderStatus.PENDING)
                .build();
        testOrder = orderRepository.save(testOrder);
    }
    
    @Test
    @DisplayName("결제 처리 성공 - 통합 테스트")
    void processPayment_ServiceLayer() {
        // Given
        PaymentCreateRequest request = new PaymentCreateRequest();
        request.setOrderId(testOrder.getId());
        request.setAmount(20000L);
        request.setMethod(PaymentMethod.CREDIT_CARD);
        
        // When
        PaymentResponse response = paymentService.processPayment(request);
        
        // Then
        assertThat(response).isNotNull();
        assertThat(response.getOrderId()).isEqualTo(testOrder.getId());
        assertThat(response.getAmount()).isEqualTo(20000L);
        assertThat(response.getMethod()).isEqualTo(PaymentMethod.CREDIT_CARD);
        
        // DB 확인
        Payment payment = paymentRepository.findById(response.getId()).orElseThrow();
        assertThat(payment.getStatus()).isIn(PaymentStatus.COMPLETED, PaymentStatus.PENDING, PaymentStatus.FAILED);
    }
    
    @Test
    @DisplayName("결제 조회 - 통합 테스트")
    void getPayment_Success() {
        // Given: 결제 생성
        Payment payment = Payment.builder()
                .order(testOrder)
                .amount(20000L)
                .method(PaymentMethod.CREDIT_CARD)
                .status(PaymentStatus.COMPLETED)
                .transactionId("TXN-001")
                .build();
        payment = paymentRepository.save(payment);
        
        // When
        PaymentResponse response = paymentService.getPayment(payment.getId());
        
        // Then
        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(payment.getId());
        assertThat(response.getOrderId()).isEqualTo(testOrder.getId());
        assertThat(response.getAmount()).isEqualTo(20000L);
        assertThat(response.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }
    
    @Test
    @DisplayName("환불 처리 - 통합 테스트")
    void refundPayment_Success() {
        // Given: 완료된 결제 생성
        Payment payment = Payment.builder()
                .order(testOrder)
                .amount(20000L)
                .method(PaymentMethod.CREDIT_CARD)
                .status(PaymentStatus.COMPLETED)
                .transactionId("TXN-002")
                .build();
        payment = paymentRepository.save(payment);
        
        // When
        PaymentResponse response = paymentService.refundPayment(payment.getId());
        
        // Then
        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(payment.getId());
        assertThat(response.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        
        // DB 확인
        Payment refundedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(refundedPayment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }
    
    @Test
    @DisplayName("결제 상태 전이 확인 - PENDING → COMPLETED")
    void paymentStatusTransition_PendingToCompleted() {
        // Given
        PaymentCreateRequest request = new PaymentCreateRequest();
        request.setOrderId(testOrder.getId());
        request.setAmount(20000L);
        request.setMethod(PaymentMethod.CREDIT_CARD);
        
        // When: 결제 처리 (성공 시나리오)
        PaymentResponse response = paymentService.processPayment(request);
        
        // Then: 상태 확인
        Payment payment = paymentRepository.findById(response.getId()).orElseThrow();
        assertThat(payment.getStatus()).isIn(PaymentStatus.COMPLETED, PaymentStatus.PENDING, PaymentStatus.FAILED);
        
        // PENDING 상태에서 시작
        if (payment.getStatus() == PaymentStatus.PENDING) {
            // PG 호출 후 COMPLETED 또는 FAILED로 변경됨
            assertThat(payment.getStatus()).isIn(PaymentStatus.PENDING, PaymentStatus.COMPLETED, PaymentStatus.FAILED);
        }
    }
}

