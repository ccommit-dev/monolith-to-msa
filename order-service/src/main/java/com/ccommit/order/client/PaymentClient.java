package com.ccommit.order.client;

import com.ccommit.order.dto.PaymentCreateRequest;
import com.ccommit.order.dto.PaymentResponse;
import com.ccommit.order.exception.PaymentServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
public class PaymentClient {

    private final RestTemplate restTemplate;

    @Value("${payment.service.url}")
    private String paymentServiceUrl;

    @Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 300, multiplier = 2))
    public PaymentResponse processPayment(PaymentCreateRequest request) {
        try {
            ResponseEntity<PaymentResponse> response = restTemplate.postForEntity(
                    paymentServiceUrl + "/api/payments",
                    request,
                    PaymentResponse.class
            );
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new PaymentServiceException("결제 서비스 응답이 비정상입니다");
            }
            return response.getBody();
        } catch (RestClientException ex) {
            throw new PaymentServiceException("결제 서비스 호출 실패", ex);
        }
    }
}
