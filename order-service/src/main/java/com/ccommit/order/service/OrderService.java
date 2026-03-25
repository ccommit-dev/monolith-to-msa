package com.ccommit.order.service;

import com.ccommit.order.dto.OrderCreateRequest;
import com.ccommit.order.dto.OrderResponse;

public interface OrderService {
    OrderResponse createOrder(OrderCreateRequest request);
    OrderResponse getOrder(Long id);
}
