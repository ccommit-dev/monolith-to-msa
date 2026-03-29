package com.ccommit.order.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PaymentResponse {

    private Long id;
    private Long orderId;
    private Long amount;
    private String method;
    private String status;
    private String transactionId;
}
