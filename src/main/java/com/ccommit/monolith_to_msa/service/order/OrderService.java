package com.ccommit.monolith_to_msa.service.order;

import com.ccommit.monolith_to_msa.dto.order.OrderCreateRequest;
import com.ccommit.monolith_to_msa.dto.order.OrderResponse;
import com.ccommit.monolith_to_msa.domain.order.OrderStatus;

import java.util.List;

/**
 * Order 서비스 인터페이스
 * DIP (Dependency Inversion Principle) 적용
 * Controller는 이 인터페이스에 의존
 */
public interface OrderService {
    
    OrderResponse createOrder(OrderCreateRequest request);
    
    List<OrderResponse> getAllOrders();
    
    OrderResponse getOrder(Long id);
    
    OrderResponse getOrderByCustomerId(Long id, String customerId);
    
    List<OrderResponse> getOrdersByCustomerId(String customerId);
    
    List<OrderResponse> getOrdersByStatus(OrderStatus status);
    
    OrderResponse updateOrderStatus(Long id, OrderStatus status);
    
    void cancelOrder(Long id, String customerId);
}

