package com.ccommit.monolith_to_msa.client;

import com.ccommit.monolith_to_msa.dto.payment.PaymentCreateRequest;
import com.ccommit.monolith_to_msa.dto.payment.PaymentResponse;
import com.ccommit.monolith_to_msa.exception.PaymentServiceException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Payment Service REST 클라이언트 (WebClient + Resilience4j Reactor 연산자)
 * — 서블릿 스택에서 .block()으로 호출할 때는 트랜잭션·스레드 블로킹에 유의.
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
    private final String paymentServiceUrl;

    public PaymentClient(
            @Qualifier("paymentWebClient") WebClient webClient,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            @Value("${payment.service.url:http://localhost:8081}") String paymentServiceUrl) {
        this.webClient = webClient;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry = retryRegistry;
        this.paymentServiceUrl = paymentServiceUrl;
    }

    /**
     * 결제 요청: Retry → CircuitBreaker → timeout 후 실패 시 Fallback(PENDING) 응답.
     */
    public Mono<PaymentResponse> processPayment(PaymentCreateRequest request) {
        log.info("Payment Service 호출 시작 (WebClient): URL={}, 주문ID={}, 금액={}",
                paymentServiceUrl, request.getOrderId(), request.getAmount());

        Mono<PaymentResponse> upstream = webClient.post()
                .uri(paymentServiceUrl + "/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> Mono.error(new PaymentServiceException(
                                String.format("결제 서비스 응답 오류: 상태코드=%s", response.statusCode()))))
                .bodyToMono(PaymentResponse.class)
                .doOnSuccess(response -> log.info("Payment Service 호출 성공: 결제ID={}, 상태={}",
                        response.getId(), response.getStatus()))
                .doOnError(error -> log.error("Payment Service 호출 실패: 주문ID={}, 오류={}",
                        request.getOrderId(), error.getMessage(), error));

        return upstream
                .transformDeferred(RetryOperator.of(retryRegistry.retry("paymentService")))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreakerRegistry.circuitBreaker("paymentService")))
                .timeout(Duration.ofSeconds(5))
                .onErrorResume(ex -> processPaymentFallback(request, toException(ex)));
    }

    /** 동기 호출 (OrderServiceImpl 등) */
    public PaymentResponse processPaymentBlocking(PaymentCreateRequest request) {
        return processPayment(request)
                .blockOptional()
                .orElseThrow(() -> new PaymentServiceException("결제 서비스 호출 실패: 응답 없음"));
    }

    private Mono<PaymentResponse> processPaymentFallback(PaymentCreateRequest request, Exception ex) {
        log.warn("Payment Service Fallback 실행: 주문ID={}, 오류={}", request.getOrderId(), ex.getMessage());
        PaymentResponse fallbackResponse = PaymentResponse.builder()
                .id(null)
                .orderId(request.getOrderId())
                .amount(request.getAmount())
                .method(request.getMethod())
                .status(com.ccommit.monolith_to_msa.domain.payment.PaymentStatus.PENDING)
                .transactionId(null)
                .build();
        log.info("Payment Fallback 응답 생성: 주문ID={}, 상태=PENDING", request.getOrderId());
        return Mono.just(fallbackResponse);
    }

    public Mono<PaymentResponse> getPayment(Long paymentId) {
        log.info("Payment Service 결제 조회 (WebClient): URL={}, 결제ID={}", paymentServiceUrl, paymentId);

        Mono<PaymentResponse> upstream = webClient.get()
                .uri(paymentServiceUrl + "/api/payments/" + paymentId)
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> Mono.error(new PaymentServiceException(
                                String.format("결제 조회 실패: 상태코드=%s", response.statusCode()))))
                .bodyToMono(PaymentResponse.class);

        return upstream
                .transformDeferred(RetryOperator.of(retryRegistry.retry("paymentService")))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreakerRegistry.circuitBreaker("paymentService")))
                .timeout(Duration.ofSeconds(5))
                .onErrorResume(ex -> getPaymentFallback(paymentId, toException(ex)));
    }

    private Mono<PaymentResponse> getPaymentFallback(Long paymentId, Exception ex) {
        log.warn("Payment Service 조회 Fallback 실행: 결제ID={}, 오류={}", paymentId, ex.getMessage());
        return Mono.error(new PaymentServiceException("결제 조회 실패: " + ex.getMessage(), ex));
    }

    public PaymentResponse getPaymentBlocking(Long paymentId) {
        return getPayment(paymentId)
                .blockOptional()
                .orElseThrow(() -> new PaymentServiceException("결제 조회 실패: 응답 없음"));
    }

    private static Exception toException(Throwable ex) {
        if (ex instanceof Exception e) {
            return e;
        }
        return new RuntimeException(ex);
    }
}
