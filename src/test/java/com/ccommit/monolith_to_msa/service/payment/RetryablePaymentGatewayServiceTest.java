package com.ccommit.monolith_to_msa.service.payment;

import com.ccommit.monolith_to_msa.domain.payment.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "spring.retry.enabled=true")
class RetryablePaymentGatewayServiceTest {

    @MockitoBean
    private PaymentGatewayService paymentGatewayService;

    @Autowired
    private RetryablePaymentGatewayService retryablePaymentGatewayService;

    @Test
    void retriesPaymentGatewayExceptionAndSucceedsOnThirdAttempt() {
        when(paymentGatewayService.requestPayment(20_000L, PaymentMethod.CREDIT_CARD))
                .thenThrow(new PaymentGatewayException("첫 번째 실패"))
                .thenThrow(new PaymentGatewayException("두 번째 실패"))
                .thenReturn("TXN-001");

        String transactionId = retryablePaymentGatewayService.requestPayment(
                20_000L,
                PaymentMethod.CREDIT_CARD
        );

        assertThat(transactionId).isEqualTo("TXN-001");
        verify(paymentGatewayService, times(3))
                .requestPayment(20_000L, PaymentMethod.CREDIT_CARD);
    }
}
