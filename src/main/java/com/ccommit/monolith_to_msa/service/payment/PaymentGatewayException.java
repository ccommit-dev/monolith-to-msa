package com.ccommit.monolith_to_msa.service.payment;

public class PaymentGatewayException extends RuntimeException {
    
    public PaymentGatewayException(String message) {
        super(message);
    }
    
    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}

