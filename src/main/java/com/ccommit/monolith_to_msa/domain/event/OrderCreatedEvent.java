package com.ccommit.monolith_to_msa.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 주문 생성 이벤트
 * Redis Pub/Sub을 통해 발행되는 이벤트
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Long orderId;
    private String customerId;
    private String productId;
    private Integer quantity;
    private Long totalPrice;
    private String paymentMethod;
    private LocalDateTime createdAt;
    
    public static OrderCreatedEvent of(Long orderId, String customerId, String productId, 
                                      Integer quantity, Long totalPrice, String paymentMethod) {
        return OrderCreatedEvent.builder()
                .orderId(orderId)
                .customerId(customerId)
                .productId(productId)
                .quantity(quantity)
                .totalPrice(totalPrice)
                .paymentMethod(paymentMethod)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
