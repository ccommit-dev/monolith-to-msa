package com.ccommit.order.service;

import com.ccommit.order.client.PaymentClient;
import com.ccommit.order.domain.Order;
import com.ccommit.order.dto.OrderCreateRequest;
import com.ccommit.order.dto.OrderResponse;
import com.ccommit.order.dto.PaymentCreateRequest;
import com.ccommit.order.dto.PaymentResponse;
import com.ccommit.order.exception.OrderNotFoundException;
import com.ccommit.order.exception.PaymentServiceException;
import com.ccommit.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final PaymentClient paymentClient;

    @Override
    @Transactional
    public OrderResponse createOrder(OrderCreateRequest request) {
        Order order = Order.builder()
                .customerId(request.getCustomerId())
                .productId(request.getProductId())
                .quantity(request.getQuantity())
                .totalPrice(request.getTotalPrice())
                .build();

        Order savedOrder = orderRepository.save(order);

        PaymentCreateRequest paymentRequest = PaymentCreateRequest.builder()
                .orderId(savedOrder.getId())
                .amount(savedOrder.getTotalPrice())
                .method("CREDIT_CARD")
                .build();

        PaymentResponse paymentResponse = paymentClient.processPayment(paymentRequest);
        if (!"COMPLETED".equals(paymentResponse.getStatus())) {
            throw new PaymentServiceException("결제 실패로 주문을 확정할 수 없습니다");
        }

        savedOrder.confirm();
        return OrderResponse.from(savedOrder);
    }

    @Override
    public OrderResponse getOrder(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("주문을 찾을 수 없습니다: " + id));
        return OrderResponse.from(order);
    }
}
