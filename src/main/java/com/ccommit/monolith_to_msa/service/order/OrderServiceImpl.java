package com.ccommit.monolith_to_msa.service.order;

import com.ccommit.monolith_to_msa.domain.event.OrderCreatedEvent;
import com.ccommit.monolith_to_msa.domain.order.Order;
import com.ccommit.monolith_to_msa.domain.order.OrderStatus;
import com.ccommit.monolith_to_msa.domain.product.Product;
import com.ccommit.monolith_to_msa.dto.order.OrderCreateRequest;
import com.ccommit.monolith_to_msa.dto.order.OrderResponse;
import com.ccommit.monolith_to_msa.exception.InsufficientStockException;
import com.ccommit.monolith_to_msa.exception.ProductNotFoundException;
import com.ccommit.monolith_to_msa.repository.order.OrderRepository;
import com.ccommit.monolith_to_msa.repository.product.ProductRepository;
import com.ccommit.monolith_to_msa.service.event.EventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Order 서비스 구현체
 * Repository 인터페이스에 의존 (DIP 적용)
 * 결제는 Redis 이벤트(`OrderCreated`) 후 비동기 Consumer·Payment API로 처리
 */
@Service
@Transactional(readOnly = true)
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final EventPublisher eventPublisher;
    
    @Autowired
    public OrderServiceImpl(
            OrderRepository orderRepository,
            ProductRepository productRepository,
            EventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.eventPublisher = eventPublisher;
    }

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

        // 6. 주문 생성 이벤트 발행 (Redis Pub/Sub)
        // 이벤트 기반 아키텍처: 결제 처리는 비동기 Consumer가 처리
        OrderCreatedEvent event = OrderCreatedEvent.of(
                savedOrder.getId(),
                savedOrder.getCustomerId(),
                savedOrder.getProductId(),
                savedOrder.getQuantity(),
                savedOrder.getTotalPrice(),
                request.getPaymentMethod() != null ? request.getPaymentMethod().name() : null
        );
        eventPublisher.publishOrderCreated(event);
        log.info("주문 생성 이벤트 발행 완료: 주문ID={}", savedOrder.getId());
        
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

