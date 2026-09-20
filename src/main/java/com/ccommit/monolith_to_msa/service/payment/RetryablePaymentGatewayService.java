package com.ccommit.monolith_to_msa.service.payment;

import com.ccommit.monolith_to_msa.domain.payment.PaymentMethod;
import lombok.RequiredArgsConstructor;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * PG 호출의 재시도 경계를 담당한다.
 *
 * <p>PaymentServiceImpl과 별도 Bean으로 분리해야 Spring Retry 프록시를 거쳐
 * {@link PaymentGatewayException} 발생 시 재시도가 실제로 적용된다.</p>
 */
@Service
@RequiredArgsConstructor
public class RetryablePaymentGatewayService {

    private final PaymentGatewayService paymentGatewayService;

    @Retryable(
            retryFor = PaymentGatewayException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public String requestPayment(Long amount, PaymentMethod method) {
        return paymentGatewayService.requestPayment(amount, method);
    }

    @Retryable(
            retryFor = PaymentGatewayException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public boolean requestRefund(String transactionId) {
        return paymentGatewayService.requestRefund(transactionId);
    }
}
