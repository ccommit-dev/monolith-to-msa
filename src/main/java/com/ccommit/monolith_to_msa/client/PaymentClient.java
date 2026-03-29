package com.ccommit.monolith_to_msa.client;

import com.ccommit.monolith_to_msa.dto.payment.PaymentCreateRequest;
import com.ccommit.monolith_to_msa.dto.payment.PaymentResponse;
import com.ccommit.monolith_to_msa.exception.PaymentServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Payment Service REST API 클라이언트
 * Order Service에서 Payment Service를 호출하기 위한 클라이언트
 */
@Service
@Profile("order")
@RequiredArgsConstructor
@Slf4j
public class PaymentClient {
    
    private final RestTemplate restTemplate;
    
    @Value("${payment.service.url:http://localhost:8081}")
    private String paymentServiceUrl;
    
    /**
     * Payment Service에 결제 처리 요청
     * 
     * @param request 결제 생성 요청
     * @return 결제 응답
     * @throws PaymentServiceException 결제 서비스 호출 실패 시
     */
    public PaymentResponse processPayment(PaymentCreateRequest request) {
        log.info("Payment Service 호출 시작: URL={}, 주문ID={}, 금액={}", 
                paymentServiceUrl, request.getOrderId(), request.getAmount());
        
        try {
            ResponseEntity<PaymentResponse> response = restTemplate.postForEntity(
                paymentServiceUrl + "/api/payments",
                request,
                PaymentResponse.class
            );
            
            if (response.getStatusCode() == HttpStatus.CREATED && response.getBody() != null) {
                log.info("Payment Service 호출 성공: 결제ID={}, 상태={}", 
                        response.getBody().getId(), response.getBody().getStatus());
                return response.getBody();
            } else {
                throw new PaymentServiceException(
                    String.format("결제 서비스 응답 오류: 상태코드=%s", response.getStatusCode())
                );
            }
        } catch (RestClientException e) {
            log.error("Payment Service 호출 실패: 주문ID={}, 오류={}", 
                    request.getOrderId(), e.getMessage(), e);
            throw new PaymentServiceException("결제 서비스 호출 실패: " + e.getMessage(), e);
        }
    }
    
    /**
     * Payment Service에 결제 조회 요청
     * 
     * @param paymentId 결제 ID
     * @return 결제 응답
     * @throws PaymentServiceException 결제 서비스 호출 실패 시
     */
    public PaymentResponse getPayment(Long paymentId) {
        log.info("Payment Service 결제 조회: URL={}, 결제ID={}", paymentServiceUrl, paymentId);
        
        try {
            ResponseEntity<PaymentResponse> response = restTemplate.getForEntity(
                paymentServiceUrl + "/api/payments/" + paymentId,
                PaymentResponse.class
            );
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return response.getBody();
            } else {
                throw new PaymentServiceException(
                    String.format("결제 조회 실패: 상태코드=%s", response.getStatusCode())
                );
            }
        } catch (RestClientException e) {
            log.error("Payment Service 결제 조회 실패: 결제ID={}, 오류={}", 
                    paymentId, e.getMessage(), e);
            throw new PaymentServiceException("결제 조회 실패: " + e.getMessage(), e);
        }
    }
}

