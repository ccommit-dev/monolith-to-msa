package com.ccommit.monolith_to_msa.service.order;

import com.ccommit.monolith_to_msa.client.PaymentClient;
import com.ccommit.monolith_to_msa.domain.order.Order;
import com.ccommit.monolith_to_msa.domain.order.OrderStatus;
import com.ccommit.monolith_to_msa.domain.payment.PaymentStatus;
import com.ccommit.monolith_to_msa.domain.product.Product;
import com.ccommit.monolith_to_msa.dto.order.OrderCreateRequest;
import com.ccommit.monolith_to_msa.dto.order.OrderResponse;
import com.ccommit.monolith_to_msa.dto.payment.PaymentCreateRequest;
import com.ccommit.monolith_to_msa.dto.payment.PaymentResponse;
import com.ccommit.monolith_to_msa.exception.InsufficientStockException;
import com.ccommit.monolith_to_msa.exception.PaymentServiceException;
import com.ccommit.monolith_to_msa.exception.ProductNotFoundException;
import com.ccommit.monolith_to_msa.repository.order.OrderRepository;
import com.ccommit.monolith_to_msa.repository.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Order 서비스 구현체
 * Repository 인터페이스에 의존 (DIP 적용)
 * Payment Service와의 통신을 위한 PaymentClient 사용
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final PaymentClient paymentClient;

    @Override
    @Transactional
    public OrderResponse createOrder(OrderCreateRequest request) {
        // 1. 상품 조회 (비관적 락 사용 - 동시성 제어)
        Product product = productRepository.findByProductIdWithLock(request.getProductId())
                .orElseThrow(() -> new ProductNotFoundException(request.getProductId()));

        // 2. 재고 확인
        if (!product.isStockAvailable(request.getQuantity())) {
            throw new InsufficientStockException(
                    request.getProductId(),
                    request.getQuantity(),
                    product.getStock()
            );
        }

        // 3. 재고 차감
        product.decreaseStock(request.getQuantity());

        // 4. 주문 생성
        Order order = Order.builder()
                .customerId(request.getCustomerId())
                .productId(request.getProductId())
                .quantity(request.getQuantity())
                .totalPrice(request.getTotalPrice())
                .status(OrderStatus.PENDING)
                .build();

        // 5. 주문 저장
        Order savedOrder = orderRepository.save(order);
        log.info("주문 생성 완료: 주문ID={}, 상태={}", savedOrder.getId(), savedOrder.getStatus());

        // 6. Payment Service 호출 (Non-blocking)
        try {
            PaymentCreateRequest paymentRequest = PaymentCreateRequest.builder()
                    .orderId(savedOrder.getId())
                    .amount(request.getTotalPrice())
                    .method(request.getPaymentMethod())
                    .build();
            
            // WebClient를 사용한 Non-blocking 호출
            PaymentResponse paymentResponse = paymentClient.processPayment(paymentRequest)
                    .blockOptional()
                    .orElse(null);
            
            if (paymentResponse != null) {
                // 결제 성공 또는 보류 상태 확인
                if (paymentResponse.getStatus() == PaymentStatus.COMPLETED) {
                    // 결제 완료: 주문 상태를 CONFIRMED로 변경
                    savedOrder.updateStatus(OrderStatus.CONFIRMED);
                    log.info("결제 완료: 주문ID={}, 결제ID={}, 상태=CONFIRMED", 
                            savedOrder.getId(), paymentResponse.getId());
                } else if (paymentResponse.getStatus() == PaymentStatus.PENDING) {
                    // Fallback으로 인한 보류 상태: 주문은 유지, 결제는 나중에 처리
                    log.warn("결제 보류: 주문ID={}, 상태=PENDING (Fallback)", savedOrder.getId());
                } else {
                    // 결제 실패: 주문 취소
                    savedOrder.cancel();
                    log.error("결제 실패: 주문ID={}, 상태=CANCELLED", savedOrder.getId());
                }
            } else {
                // Payment Service 응답 없음: 주문은 유지, 결제는 보류
                log.warn("Payment Service 응답 없음: 주문ID={}, 상태=PENDING (Fallback)", savedOrder.getId());
            }
        } catch (PaymentServiceException e) {
            // Payment Service 호출 실패: Fallback 처리
            // 주문은 생성되지만 결제는 보류 상태로 처리
            log.error("Payment Service 호출 실패 (Fallback): 주문ID={}, 오류={}", 
                    savedOrder.getId(), e.getMessage());
            // 주문은 PENDING 상태로 유지 (나중에 결제 처리 가능)
        }
        
        return OrderResponse.from(savedOrder);
    }

    @Override
    public List<OrderResponse> getAllOrders() {
        return orderRepository.findAll().stream()
                .map(OrderResponse::from)
                .collect(Collectors.toList());
    }

    @Override
    public OrderResponse getOrder(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + id));
        return OrderResponse.from(order);
    }

    @Override
    public OrderResponse getOrderByCustomerId(Long id, String customerId) {
        Order order = orderRepository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + id));
        return OrderResponse.from(order);
    }

    @Override
    public List<OrderResponse> getOrdersByCustomerId(String customerId) {
        return orderRepository.findByCustomerId(customerId).stream()
                .map(OrderResponse::from)
                .collect(Collectors.toList());
    }

    @Override
    public List<OrderResponse> getOrdersByStatus(OrderStatus status) {
        return orderRepository.findByStatus(status).stream()
                .map(OrderResponse::from)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public OrderResponse updateOrderStatus(Long id, OrderStatus status) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + id));
        order.updateStatus(status);
        return OrderResponse.from(order);
    }

    @Override
    @Transactional
    public void cancelOrder(Long id, String customerId) {
        Order order = orderRepository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + id));
        order.cancel();
    }
}

