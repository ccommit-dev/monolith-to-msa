package com.ccommit.monolith_to_msa.service.payment;

import com.ccommit.monolith_to_msa.dto.payment.PaymentCreateRequest;
import com.ccommit.monolith_to_msa.dto.payment.PaymentResponse;

public interface PaymentService {
    
    PaymentResponse processPayment(PaymentCreateRequest request);
    
    PaymentResponse getPayment(Long id);
    
    PaymentResponse refundPayment(Long paymentId);
}

