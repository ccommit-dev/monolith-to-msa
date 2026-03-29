package com.ccommit.payment.service;

import com.ccommit.payment.dto.PaymentCreateRequest;
import com.ccommit.payment.dto.PaymentResponse;

public interface PaymentService {
    PaymentResponse processPayment(PaymentCreateRequest request);
    PaymentResponse getPayment(Long id);
}
