package com.ccommit.order.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentCreateRequest {

    private Long orderId;
    private Long amount;
    private String method;
}
