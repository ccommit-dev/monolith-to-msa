package com.ccommit.monolith_to_msa.client;

import com.ccommit.monolith_to_msa.dto.payment.PaymentCreateRequest;
import com.ccommit.monolith_to_msa.dto.payment.PaymentResponse;
import com.ccommit.monolith_to_msa.exception.PaymentServiceException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Payment Service REST API 클라이언트 (WebClient 기반)
 * Order Service에서 Payment Service를 호출하기 위한 Non-blocking 클라이언트
 * Resilience4j를 통한 Circuit Breaker, Retry, Timeout 적용
 */
@Service
@Slf4j
@ConditionalOnClass(name = {
    "org.springframework.web.reactive.function.client.WebClient",
    "reactor.core.publisher.Mono"
})
public class PaymentClient {
    
    private final WebClient webClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    
    @Value("${payment.service.url:http://localhost:8081}")
    private String paymentServiceUrl;
    
    public PaymentClient(
            @Value("${payment.service.url:http://localhost:8081}") String paymentServiceUrl,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry) {
        this.paymentServiceUrl = paymentServiceUrl;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry = retryRegistry;
        this.webClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))  // 2MB 버퍼 크기
                .build();
    }
    
    /**
     * Payment Service에 결제 처리 요청 (Non-blocking)
     * Circuit Breaker, Retry, Timeout 적용
     * 
     * @param request 결제 생성 요청
     * @return 결제 응답 (Mono)
     */
    @CircuitBreaker(name = "paymentService", fallbackMethod = "processPaymentFallback")
    @Retry(name = "paymentService")
    @TimeLimiter(name = "paymentService")
    public Mono<PaymentResponse> processPayment(PaymentCreateRequest request) {
        log.info("Payment Service 호출 시작 (WebClient): URL={}, 주문ID={}, 금액={}", 
                paymentServiceUrl, request.getOrderId(), request.getAmount());
        
        return webClient.post()
                .uri(paymentServiceUrl + "/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(
                    status -> status.is4xxClientError() || status.is5xxServerError(),
                    response -> {
                        log.error("Payment Service 호출 실패: 상태코드={}, 주문ID={}", 
                                response.statusCode(), request.getOrderId());
                        return Mono.error(new PaymentServiceException(
                            String.format("결제 서비스 응답 오류: 상태코드=%s", response.statusCode())
                        ));
                    }
                )
                .bodyToMono(PaymentResponse.class)
                .doOnSuccess(response -> 
                    log.info("Payment Service 호출 성공: 결제ID={}, 상태={}", 
                            response.getId(), response.getStatus())
                )
                .doOnError(error -> 
                    log.error("Payment Service 호출 실패: 주문ID={}, 오류={}", 
                            request.getOrderId(), error.getMessage(), error)
                )
                .transformDeferred(CircuitBreakerOperator.of(circuitBreakerRegistry.circuitBreaker("paymentService")))
                .transformDeferred(RetryOperator.of(retryRegistry.retry("paymentService")))
                .timeout(Duration.ofSeconds(5))
                .onErrorMap(throwable -> 
                    throwable instanceof PaymentServiceException 
                        ? throwable 
                        : new PaymentServiceException("결제 서비스 호출 실패: " + throwable.getMessage(), throwable)
                );
    }
    
    /**
     * Payment Service에 결제 처리 요청 (Blocking - 호환성 유지)
     * 
     * @param request 결제 생성 요청
     * @return 결제 응답
     * @throws PaymentServiceException 결제 서비스 호출 실패 시
     */
    public PaymentResponse processPaymentBlocking(PaymentCreateRequest request) {
        return processPayment(request)
                .blockOptional()
                .orElseThrow(() -> new PaymentServiceException("결제 서비스 호출 실패: 응답 없음"));
    }
    
    /**
     * Circuit Breaker Fallback: Payment 실패 시 보류 처리
     * 주문은 생성되지만 결제는 보류 상태로 처리
     * 
     * @param request 결제 생성 요청
     * @param ex 발생한 예외
     * @return 보류 상태의 결제 응답
     */
    private Mono<PaymentResponse> processPaymentFallback(
            PaymentCreateRequest request, 
            Exception ex) {
        log.warn("Payment Service Fallback 실행: 주문ID={}, 오류={}", 
                request.getOrderId(), ex.getMessage());
        
        // Fallback: 보류 상태의 결제 응답 반환
        // 실제로는 주문은 생성되지만 결제는 나중에 처리되도록 보류 상태로 저장
        PaymentResponse fallbackResponse = PaymentResponse.builder()
                .id(null)  // 결제 ID는 없음 (보류 상태)
                .orderId(request.getOrderId())
                .amount(request.getAmount())
                .method(request.getMethod())
                .status(com.ccommit.monolith_to_msa.domain.payment.PaymentStatus.PENDING)
                .transactionId(null)
                .build();
        
        log.info("Payment Fallback 응답 생성: 주문ID={}, 상태=PENDING", request.getOrderId());
        return Mono.just(fallbackResponse);
    }
    
    /**
     * Payment Service에 결제 조회 요청 (Non-blocking)
     * 
     * @param paymentId 결제 ID
     * @return 결제 응답 (Mono)
     */
    @CircuitBreaker(name = "paymentService", fallbackMethod = "getPaymentFallback")
    @Retry(name = "paymentService")
    @TimeLimiter(name = "paymentService")
    public Mono<PaymentResponse> getPayment(Long paymentId) {
        log.info("Payment Service 결제 조회 (WebClient): URL={}, 결제ID={}", 
                paymentServiceUrl, paymentId);
        
        return webClient.get()
                .uri(paymentServiceUrl + "/api/payments/" + paymentId)
                .retrieve()
                .onStatus(
                    status -> status.is4xxClientError() || status.is5xxServerError(),
                    response -> {
                        log.error("Payment Service 결제 조회 실패: 상태코드={}, 결제ID={}", 
                                response.statusCode(), paymentId);
                        return Mono.error(new PaymentServiceException(
                            String.format("결제 조회 실패: 상태코드=%s", response.statusCode())
                        ));
                    }
                )
                .bodyToMono(PaymentResponse.class)
                .doOnSuccess(response -> 
                    log.info("Payment Service 결제 조회 성공: 결제ID={}", paymentId)
                )
                .doOnError(error -> 
                    log.error("Payment Service 결제 조회 실패: 결제ID={}, 오류={}", 
                            paymentId, error.getMessage(), error)
                )
                .transformDeferred(CircuitBreakerOperator.of(circuitBreakerRegistry.circuitBreaker("paymentService")))
                .transformDeferred(RetryOperator.of(retryRegistry.retry("paymentService")))
                .timeout(Duration.ofSeconds(5))
                .onErrorMap(throwable -> 
                    throwable instanceof PaymentServiceException 
                        ? throwable 
                        : new PaymentServiceException("결제 조회 실패: " + throwable.getMessage(), throwable)
                );
    }
    
    /**
     * Circuit Breaker Fallback: 결제 조회 실패 시
     * 
     * @param paymentId 결제 ID
     * @param ex 발생한 예외
     * @return 빈 Mono (조회 실패)
     */
    private Mono<PaymentResponse> getPaymentFallback(Long paymentId, Exception ex) {
        log.warn("Payment Service 조회 Fallback 실행: 결제ID={}, 오류={}", paymentId, ex.getMessage());
        return Mono.error(new PaymentServiceException("결제 조회 실패: " + ex.getMessage(), ex));
    }
    
    /**
     * Payment Service에 결제 조회 요청 (Blocking - 호환성 유지)
     * 
     * @param paymentId 결제 ID
     * @return 결제 응답
     * @throws PaymentServiceException 결제 서비스 호출 실패 시
     */
    public PaymentResponse getPaymentBlocking(Long paymentId) {
        return getPayment(paymentId)
                .blockOptional()
                .orElseThrow(() -> new PaymentServiceException("결제 조회 실패: 응답 없음"));
    }
}
