package com.ccommit.monolith_to_msa.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 결제 완료 이벤트
 * Redis Pub/Sub을 통해 발행되는 이벤트
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCompletedEvent implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Long paymentId;
    private Long orderId;
    private Long amount;
    private String transactionId;
    private String status;
    private LocalDateTime completedAt;
    
    public static PaymentCompletedEvent of(Long paymentId, Long orderId, Long amount, 
                                           String transactionId, String status) {
        return PaymentCompletedEvent.builder()
                .paymentId(paymentId)
                .orderId(orderId)
                .amount(amount)
                .transactionId(transactionId)
                .status(status)
                .completedAt(LocalDateTime.now())
                .build();
    }
}
