package com.ccommit.monolith_to_msa.exception;

/**
 * Payment Service 호출 실패 시 발생하는 예외
 * MSA 환경에서 Payment Service와의 통신 실패를 나타냄
 */
public class PaymentServiceException extends RuntimeException {
    
    public PaymentServiceException(String message) {
        super(message);
    }
    
    public PaymentServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}

