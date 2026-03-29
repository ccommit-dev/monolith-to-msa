# Ch06.10: 통신 구조 실습 가이드

## 실습 목표

- RestTemplate 대신 **WebClient**로 Payment 서비스 호출 (논블로킹 스택 기반)
- **Resilience4j**로 Circuit Breaker·Retry 적용, Reactor **`timeout`**으로 상한 시간 제어
- Payment 실패 시 **`onErrorResume` + Fallback**으로 보류(PENDING) 응답 처리
- **Actuator**로 Circuit Breaker 상태 확인

---

## 실습 진행 순서 (파일 기준)

아래 순서대로 읽고·실행하면 저장소 상태와 맞습니다.

| 순서 | 단계 | 다루는 파일 / 작업 | 확인 |
|:---:|:---|:---|:---|
| 0 | 개념 | 아래 **서비스 구조 요약** | 포트·역할 이해 |
| 1 | 의존성 | `build.gradle` | WebFlux, Resilience4j 의존성 존재 |
| 2 | Payment URL·복원력 설정 | `src/main/resources/application-order.yaml` | `payment.service.url`, `resilience4j.*`, `management.endpoints` |
| 3 | WebClient 빈 | `PaymentWebClientConfig.java` | `@Qualifier("paymentWebClient")` 빈 등록 |
| 4 | 클라이언트 + 복원력 + 폴백 | `PaymentClient.java` | Retry → CB → timeout → `onErrorResume` |
| 5 | 주문 흐름 연동 | `OrderServiceImpl`, `OrderCreateRequest` | `paymentMethod` 필수, `Optional<PaymentClient>` |
| 6 | 검증 | `./gradlew test`, 로컬 curl / Docker | 테스트 통과, Fallback·CB 동작 |

프로필: Order 실습 시 **`spring.profiles.active=order`**(또는 해당 프로필로 기동)을 사용합니다.

**표기:** 본 문서에서 **`〔신규〕`** 는 이번 통신 실습에서 **추가한 코드**, **`〔수정〕`** 은 **변경·보강한 블록**을 가리킵니다.

---

## 서비스 구조 요약

- **Order Service** (예: 8080): 주문 생성·조회. Payment는 **HTTP**로 호출.
- **Payment Service** (예: 8081): 결제 API. Order와 DB 분리.
- **통신**: REST + `WebClient`. **장애**: Retry, Circuit Breaker, 타임아웃, Fallback.

---

## 1단계: 의존성 (`build.gradle`)

**위치:** 프로젝트 루트 `build.gradle`

**요지:** `spring-boot-starter-webflux`, `reactor-netty-http`, `reactor-core`, `resilience4j-spring-boot3`, `resilience4j-reactor`가 포함되어야 합니다.

**설명:** `webflux`는 서버를 쓰지 않아도 **클라이언트(WebClient)** 와 Reactor 타입(`Mono`)에 필요합니다. `resilience4j-reactor`는 `RetryOperator`, `CircuitBreakerOperator`를 제공합니다.

**확인:** Gradle 동기화 후 컴파일 오류 없음.

### 1단계 — `build.gradle` 전체 (저장소 기준)

```gradle
plugins {
	id 'java'
	id 'org.springframework.boot' version '4.0.2'
	id 'io.spring.dependency-management' version '1.1.7'
}

group = 'com.ccommit'
version = '0.0.1-SNAPSHOT'
description = 'monolith-to-msa for Spring Boot'

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

configurations {
	compileOnly {
		extendsFrom annotationProcessor
	}
}

repositories {
	mavenCentral()
}

dependencies {
	// Web
	implementation 'org.springframework.boot:spring-boot-starter-web'

	// Actuator (Health Check)
	implementation 'org.springframework.boot:spring-boot-starter-actuator'
	
	// Validation
	implementation 'org.springframework.boot:spring-boot-starter-validation'
	
	// JPA
	implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
	
	// Retry
	implementation 'org.springframework.retry:spring-retry:2.0.5'
	implementation 'org.springframework:spring-aspects'

	// Database
	implementation 'com.h2database:h2'
	runtimeOnly 'com.mysql:mysql-connector-j'
	
	// Redis Cache
	implementation 'org.springframework.boot:spring-boot-starter-data-redis'
	implementation 'org.springframework.boot:spring-boot-starter-cache'
	
	// Lombok
	compileOnly 'org.projectlombok:lombok'
	annotationProcessor 'org.projectlombok:lombok'
	
	// Dev Tools
	developmentOnly 'org.springframework.boot:spring-boot-devtools'

	// WebClient (Reactive Web)
	implementation 'org.springframework.boot:spring-boot-starter-webflux'
	implementation 'io.projectreactor.netty:reactor-netty-http'  // Reactor Netty HTTP 클라이언트
	implementation 'io.projectreactor:reactor-core'  // Reactor Core (Mono, Flux 등)

	// Resilience4j (Circuit Breaker, Retry, Timeout) — Spring Boot 4 Actuator 연동은 2.3.x 권장
	implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.3.0'
	implementation 'io.github.resilience4j:resilience4j-reactor:2.3.0'

	// Test
	testImplementation 'org.springframework.boot:spring-boot-starter-test'
	testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
	useJUnitPlatform()
}

// Retry 활성화
tasks.named('compileJava') {
	options.annotationProcessorPath = configurations.annotationProcessor
}

// plain.jar 생성 비활성화 (실행 가능한 JAR만 생성)
jar {
	enabled = false
}

// bootJar만 활성화 (실행 가능한 JAR)
bootJar {
	enabled = true
	archiveClassifier = '' // classifier 제거하여 기본 이름 사용
}
```

---

## 2단계: 설정 (`application-order.yaml`)

**위치:** `src/main/resources/application-order.yaml`

- **`payment.service.url`**: `PaymentClient`의 `@Value("${payment.service.url:...}")`와 연결.
- **`management`**: `/actuator/circuitbreakers`, `/actuator/circuitbreakerevents` 등 노출.
- **`resilience4j`**: 인스턴스 이름 **`paymentService`** — 코드의 `retry("paymentService")`, `circuitBreaker("paymentService")`와 동일해야 함.
- **`timelimiter`**: YAML에 정의되어 있으나, **현재 `PaymentClient`는 `@TimeLimiter` 미사용**이며 실제 상한은 **`Mono.timeout(Duration.ofSeconds(5))`** 입니다.

**확인:** order 프로필로 기동 후 `GET /actuator/health` 응답에 circuit breaker 관련 항목이 보이는지 확인.

### 2단계 — `application-order.yaml` 전체 (저장소 기준)

```yaml
# Order Service 설정 (실습용 예제)
# 실제 서비스 분리 시 별도 프로젝트의 application.yaml로 구성

server:
  port: 8080
  error:
    include-message: always
    include-binding-errors: always

spring:
  application:
    name: order-service
  
  # Database 설정 (Order DB)
  datasource:
    url: jdbc:h2:mem:orderdb
    driver-class-name: org.h2.Driver
    username: sa
    password: sa
    # 독립적인 커넥션 풀 (Order Service 전용)
    hikari:
      maximum-pool-size: 20  # 주문 서비스 전용 커넥션 풀
      minimum-idle: 10
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
      leak-detection-threshold: 60000
  
  # JPA 설정
  jpa:
    hibernate:
      ddl-auto: create-drop
    show-sql: true
    properties:
      hibernate:
        format_sql: true
        dialect: org.hibernate.dialect.H2Dialect
    defer-datasource-initialization: true
  
  # SQL 초기화 설정
  sql:
    init:
      mode: always
      continue-on-error: false
  
  # H2 Console
  h2:
    console:
      enabled: true
      path: /h2-console
      settings:
        web-allow-others: true
        trace: false

# Payment Service URL (환경 변수로 주입 가능)
payment:
  service:
    url: ${PAYMENT_SERVICE_URL:http://localhost:8081}

# Actuator 설정
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus,circuitbreakers,circuitbreakerevents
  endpoint:
    health:
      show-details: always
  metrics:
    export:
      prometheus:
        enabled: true
  health:
    circuitbreakers:
      enabled: true

# Resilience4j 설정
resilience4j:
  circuitbreaker:
    instances:
      paymentService:
        registerHealthIndicator: true
        slidingWindowSize: 10                    # 슬라이딩 윈도우 크기
        minimumNumberOfCalls: 5                  # 최소 호출 횟수
        permittedNumberOfCallsInHalfOpenState: 3 # Half-Open 상태에서 허용되는 호출 수
        automaticTransitionFromOpenToHalfOpenEnabled: true
        waitDurationInOpenState: 10s             # Open 상태 유지 시간
        failureRateThreshold: 50                 # 실패율 임계값 (50%)
        slowCallRateThreshold: 100                # 느린 호출 임계값 (100%)
        slowCallDurationThreshold: 2s             # 느린 호출 기준 시간
        recordExceptions:
          - com.ccommit.monolith_to_msa.exception.PaymentServiceException
          - org.springframework.web.reactive.function.client.WebClientException
          - java.util.concurrent.TimeoutException
        ignoreExceptions:
          - com.ccommit.monolith_to_msa.exception.OrderException
  
  retry:
    instances:
      paymentService:
        maxAttempts: 3                           # 최대 재시도 횟수
        waitDuration: 1s                         # 재시도 대기 시간
        enableExponentialBackoff: true           # 지수 백오프 활성화
        exponentialBackoffMultiplier: 2           # 지수 백오프 배수
        retryExceptions:
          - com.ccommit.monolith_to_msa.exception.PaymentServiceException
          - org.springframework.web.reactive.function.client.WebClientException
          - java.util.concurrent.TimeoutException
        ignoreExceptions:
          - com.ccommit.monolith_to_msa.exception.OrderException
  
  timelimiter:
    instances:
      paymentService:
        timeoutDuration: 5s                      # 타임아웃 시간
        cancelRunningFuture: true                 # 실행 중인 Future 취소
```

---

## 3단계: Payment 전용 WebClient 빈 (`PaymentWebClientConfig.java`)

**위치:** `src/main/java/com/ccommit/monolith_to_msa/config/PaymentWebClientConfig.java`

**역할:** Payment 전용 Netty `HttpClient` 타임아웃, `@Qualifier("paymentWebClient")`, `WebClient.builder()` 정적 생성(Spring Boot 4 MVC만 쓸 때 `WebClient.Builder` 빈 없음 대비), `maxInMemorySize` 2MB.

**실습 포인트:** 외부 연동 단위로 Connector 설정을 분리하는 패턴.

### 3단계 — `PaymentWebClientConfig.java` 전체 (저장소 기준)

```java
package com.ccommit.monolith_to_msa.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Payment 서비스 호출 전용 WebClient (연결·읽기·쓰기 타임아웃)
 */
@Configuration
public class PaymentWebClientConfig {

    @Bean
    @Qualifier("paymentWebClient")
    public WebClient paymentWebClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(Duration.ofSeconds(10))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(10, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(10, TimeUnit.SECONDS)));

        // Spring Boot 4 + MVC 위주 구성에서는 WebClient.Builder 빈이 없을 수 있어 정적 빌더 사용
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }
}
```

---

## 4단계: `PaymentClient` — WebClient + Resilience4j Reactor + Fallback

**위치:** `src/main/java/com/ccommit/monolith_to_msa/client/PaymentClient.java`

**요약:**

- 주입: `@Qualifier("paymentWebClient")`, `CircuitBreakerRegistry`, `RetryRegistry`, `payment.service.url`.
- **연산자 순서:** `RetryOperator` → `CircuitBreakerOperator` → `timeout(5s)` → `onErrorResume`(결제 생성은 PENDING 폴백, 조회는 `Mono.error`).
- 어노테이션 AOP 대신 **Reactor 연산자만** 사용해 이중 적용을 피함.
- `processPaymentBlocking` / `getPaymentBlocking`: 서블릿 쪽에서 `.blockOptional()` 호출용.

### `processPayment` — Javadoc 한 줄 요약 **외** 추가 설명

소스의 주석은 아래 한 줄로 요약되어 있습니다.

```java
/**
 * 결제 요청: Retry → CircuitBreaker → timeout 후 실패 시 Fallback(PENDING) 응답.
 */
```

**추가로 알아둘 내용:**

- **`upstream` 체인:** `webClient.post()` → `retrieve()` → HTTP **4xx/5xx** 는 `onStatus`에서 `PaymentServiceException`으로 바꿔 **복원력 정책이 재시도·CB 대상으로 인식**하게 함.
- **`doOnSuccess` / `doOnError`:** 스트림 결과를 바꾸지 않고 **로깅만** 수행.
- **Retry를 CB보다 앞에 둔 이유:** 일시 오류는 먼저 재시도하고, 그 **시도 묶음**을 서킷이 집계하는 식으로 동작을 맞춤(YAML의 `paymentService` 인스턴스와 연동).
- **`timeout(5s)`:** 전체 파이프라인 상한. YAML `timelimiter`와 별개로, 여기서 실제로 끊김.
- **`onErrorResume` → `processPaymentFallback`:** 연결 실패·타임아웃·CB 차단·4xx/5xx 등 **어느 단계에서든** 실패 시 주문 쪽이 이어질 수 있도록 **가짜 성공에 가까운 PENDING** 응답을 돌려줌(주문 서비스는 이 값으로 `CONFIRMED` vs `PENDING` 분기).
- **`getPayment`:** 조회는 PENDING 폴백 대신 **`Mono.error`** — 없는 결제를 성공처럼 주면 안 되기 때문.
- **`@ConditionalOnClass`:** WebClient/Reactor 클래스가 없는 환경에서는 빈 미등록(테스트·프로필 분리 시 유의).

### 4단계 — `PaymentClient.java` 전체 (저장소 기준)

```java
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
```

---

## 5단계: 주문 서비스 연동 (`OrderServiceImpl` · `OrderCreateRequest`)

**위치:**

- `src/main/java/com/ccommit/monolith_to_msa/service/order/OrderServiceImpl.java`
- `src/main/java/com/ccommit/monolith_to_msa/dto/order/OrderCreateRequest.java`

**요약:**

- `Optional<PaymentClient>`: Payment 빈이 없는 프로필/테스트에서 주문만 동작.
- `createOrder`: 저장 후 `PaymentCreateRequest`(orderId, amount, **paymentMethod**)로 `processPayment` → `COMPLETED`/`PENDING`/기타·null 분기.
- API·테스트 JSON에 **`paymentMethod`** 필수 (`@NotNull`).

**〔수정〕 `OrderServiceImpl#createOrder`**  
주문 저장 이후 **`// 6. Payment Service 호출 (Non-blocking)`** 부터 **`if (paymentClient.isPresent()) { ... } else { ... }`** 까지의 블록이 통신 실습의 핵심 수정 구간입니다. `Optional<PaymentClient>` 주입, `PaymentCreateRequest` 빌드, `processPayment(...).blockOptional()`, `COMPLETED` / `PENDING` / 예외 처리, `PaymentClient` 부재 시 주문만 생성하는 분기를 포함합니다.

```json
{
  "customerId": "customer1",
  "productId": "product1",
  "quantity": 2,
  "totalPrice": 20000,
  "paymentMethod": "CREDIT_CARD"
}
```

### 5단계 — `OrderCreateRequest.java` 전체 (저장소 기준)

**〔신규〕** 아래 **`paymentMethod`** 필드와 `PaymentMethod` import·`@NotNull` 검증은 통신 실습에서 **주문 API가 결제 수단을 함께 넘기도록** 하기 위해 추가한 부분입니다.

```java
package com.ccommit.monolith_to_msa.dto.order;

import com.ccommit.monolith_to_msa.domain.payment.PaymentMethod;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class OrderCreateRequest {

    @NotBlank(message = "고객 ID는 필수입니다")
    private String customerId;

    @NotBlank(message = "상품 ID는 필수입니다")
    private String productId;

    @NotNull(message = "수량은 필수입니다")
    @Min(value = 1, message = "수량은 1개 이상이어야 합니다")
    private Integer quantity;

    @NotNull(message = "총 가격은 필수입니다")
    @Min(value = 0, message = "총 가격은 0원 이상이어야 합니다")
    private Long totalPrice;

    // 〔신규〕 결제 연동 — REST 본문·검증에 필수 (예: "CREDIT_CARD")
    @NotNull(message = "결제 수단은 필수입니다")
    private PaymentMethod paymentMethod;
}
```

### 5단계 — `OrderServiceImpl.java` 전체 (저장소 기준)

**〔수정〕** 아래 코드에서 **`// 6. Payment Service 호출`** 부터 **`if (paymentClient.isPresent())`** 로 시작하는 블록 전체(대응하는 `else` 포함)가 실습에서 손대는 구간입니다.

```java
package com.ccommit.monolith_to_msa.service.order;

import com.ccommit.monolith_to_msa.client.PaymentClient;
import com.ccommit.monolith_to_msa.domain.order.Order;
import com.ccommit.monolith_to_msa.domain.order.OrderStatus;
import com.ccommit.monolith_to_msa.domain.payment.PaymentStatus;
import com.ccommit.monolith_to_msa.domain.product.Product;
import com.ccommit.monolith_to_msa.dto.order.OrderCreateRequest;
import com.ccommit.monolith_to_msa.dto.order.OrderResponse;
import com.ccommit.monolith_to_msa.dto.payment.PaymentCreateRequest;
import com.ccommit.monolith_to_msa.dto.payment.PaymentResponse;
import com.ccommit.monolith_to_msa.exception.InsufficientStockException;
import com.ccommit.monolith_to_msa.exception.PaymentServiceException;
import com.ccommit.monolith_to_msa.exception.ProductNotFoundException;
import com.ccommit.monolith_to_msa.repository.order.OrderRepository;
import com.ccommit.monolith_to_msa.repository.product.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Order 서비스 구현체
 * Repository 인터페이스에 의존 (DIP 적용)
 * Payment Service와의 통신을 위한 PaymentClient 사용
 */
@Service
@Transactional(readOnly = true)
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final Optional<PaymentClient> paymentClient;
    
    @Autowired
    public OrderServiceImpl(
            OrderRepository orderRepository,
            ProductRepository productRepository,
            Optional<PaymentClient> paymentClient) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.paymentClient = paymentClient;
    }

    @Override
    @Transactional
    public OrderResponse createOrder(OrderCreateRequest request) {
        // 1. 상품 조회 (비관적 락 사용 - 동시성 제어)
        Product product = productRepository.findByProductIdWithLock(request.getProductId())
                .orElseThrow(() -> new ProductNotFoundException(request.getProductId()));

        // 2. 재고 확인
        if (!product.isStockAvailable(request.getQuantity())) {
            throw new InsufficientStockException(
                    request.getProductId(),
                    request.getQuantity(),
                    product.getStock()
            );
        }

        // 3. 재고 차감
        product.decreaseStock(request.getQuantity());

        // 4. 주문 생성
        Order order = Order.builder()
                .customerId(request.getCustomerId())
                .productId(request.getProductId())
                .quantity(request.getQuantity())
                .totalPrice(request.getTotalPrice())
                .status(OrderStatus.PENDING)
                .build();

        // 5. 주문 저장
        Order savedOrder = orderRepository.save(order);
        log.info("주문 생성 완료: 주문ID={}, 상태={}", savedOrder.getId(), savedOrder.getStatus());

        // 〔수정〕 ─── 통신 실습 블록 시작 ───
        // 6. Payment Service 호출 (Non-blocking)
        if (paymentClient.isPresent()) {
            try {
                PaymentCreateRequest paymentRequest = PaymentCreateRequest.builder()
                        .orderId(savedOrder.getId())
                        .amount(request.getTotalPrice())
                        .method(request.getPaymentMethod())
                        .build();
                
                // WebClient를 사용한 Non-blocking 호출
                PaymentResponse paymentResponse = paymentClient.get().processPayment(paymentRequest)
                        .blockOptional()
                        .orElse(null);
                
                if (paymentResponse != null) {
                    // 결제 성공 또는 보류 상태 확인
                    if (paymentResponse.getStatus() == PaymentStatus.COMPLETED) {
                        // 결제 완료: 주문 상태를 CONFIRMED로 변경
                        savedOrder.updateStatus(OrderStatus.CONFIRMED);
                        log.info("결제 완료: 주문ID={}, 결제ID={}, 상태=CONFIRMED", 
                                savedOrder.getId(), paymentResponse.getId());
                    } else if (paymentResponse.getStatus() == PaymentStatus.PENDING) {
                        // Fallback으로 인한 보류 상태: 주문은 유지, 결제는 나중에 처리
                        log.warn("결제 보류: 주문ID={}, 상태=PENDING (Fallback)", savedOrder.getId());
                    } else {
                        // 결제 실패: 주문 취소
                        savedOrder.cancel();
                        log.error("결제 실패: 주문ID={}, 상태=CANCELLED", savedOrder.getId());
                    }
                } else {
                    // Payment Service 응답 없음: 주문은 유지, 결제는 보류
                    log.warn("Payment Service 응답 없음: 주문ID={}, 상태=PENDING (Fallback)", savedOrder.getId());
                }
            } catch (PaymentServiceException e) {
                // Payment Service 호출 실패: Fallback 처리
                // 주문은 생성되지만 결제는 보류 상태로 처리
                log.error("Payment Service 호출 실패 (Fallback): 주문ID={}, 오류={}", 
                        savedOrder.getId(), e.getMessage());
                // 주문은 PENDING 상태로 유지 (나중에 결제 처리 가능)
            }
        } else {
            // PaymentClient가 없을 경우: 주문만 생성 (결제는 나중에 처리)
            log.warn("PaymentClient가 없습니다. 주문만 생성: 주문ID={}, 상태=PENDING", savedOrder.getId());
        }
        // 〔수정〕 ─── 통신 실습 블록 끝 ───
        
        return OrderResponse.from(savedOrder);
    }

    @Override
    public List<OrderResponse> getAllOrders() {
        return orderRepository.findAll().stream()
                .map(OrderResponse::from)
                .collect(Collectors.toList());
    }

    @Override
    public OrderResponse getOrder(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + id));
        return OrderResponse.from(order);
    }

    @Override
    public OrderResponse getOrderByCustomerId(Long id, String customerId) {
        Order order = orderRepository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + id));
        return OrderResponse.from(order);
    }

    @Override
    public List<OrderResponse> getOrdersByCustomerId(String customerId) {
        return orderRepository.findByCustomerId(customerId).stream()
                .map(OrderResponse::from)
                .collect(Collectors.toList());
    }

    @Override
    public List<OrderResponse> getOrdersByStatus(OrderStatus status) {
        return orderRepository.findByStatus(status).stream()
                .map(OrderResponse::from)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public OrderResponse updateOrderStatus(Long id, OrderStatus status) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + id));
        order.updateStatus(status);
        return OrderResponse.from(order);
    }

    @Override
    @Transactional
    public void cancelOrder(Long id, String customerId) {
        Order order = orderRepository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + id));
        order.cancel();
    }
}
```

---

## 6단계: 통합·수동 검증

### 6.1 자동 테스트

```bash
./gradlew test
```

### 6.2 정상: 주문 + 결제 성공

Order·Payment 프로세스를 띄운 뒤:

```bash
curl -X POST http://localhost:8080/api/orders ^
  -H "Content-Type: application/json" ^
  -d "{\"customerId\":\"customer1\",\"productId\":\"product1\",\"quantity\":2,\"totalPrice\":20000,\"paymentMethod\":\"CREDIT_CARD\"}"
```

( bash: 줄 끝 `^` 대신 `\` 사용. )

### 6.3 Payment 장애 + Fallback (실습 방법)

**전제:** Order 서비스는 **실행 중**, `payment.service.url`(기본 `http://localhost:8081`)이 가리키는 Payment 프로세스만 막으면 됩니다. 프로필은 `order`(또는 Docker면 `docker`) 등 **PaymentClient 빈이 뜨는 구성**이어야 합니다.

#### (1) 장애 내는 방법 (택 1)

**A. Docker Compose로 띄운 경우**

```bash
docker compose -f docker-compose-msa.yml stop payment-service
```

또는 컨테이너 이름으로:

```bash
docker stop payment-service
```

Order는 그대로 두고 Payment만 중지합니다.

**B. 로컬에서 JVM 두 개로 띄운 경우**

Payment 애플리케이션(8081) 터미널에서 **중지**(Ctrl+C). Order(8080)는 계속 실행.

**C. 방화벽·잘못된 URL(선택)**

`PAYMENT_SERVICE_URL`을 존재하지 않는 호스트로 바꿔 재기동하면 연결 실패로 동일하게 Retry 후 Fallback으로 이어질 수 있습니다.

#### (2) 동일 주문 생성 요청 다시 보내기

6.2와 같은 `POST /api/orders` 요청을 한 번 이상 보냅니다. Payment가 없으면 연결 실패·타임아웃 등이 나고, `PaymentClient`의 **`onErrorResume`** 이 **PENDING** 결제 응답을 돌려줍니다.

#### (3) HTTP 응답으로 확인

- **201 Created** 등으로 **주문은 생성**됩니다.
- 응답 JSON의 **`status`** 가 **`PENDING`** 인 경우가 많습니다(결제는 보류, 주문 확정 전).

#### (4) Fallback 로그 확인 방법

코드 기준으로 아래 문자열이 **Order 서비스 로그**에 나오면 Fallback 경로를 탄 것입니다.

| 출처 | 로그에 포함되기 쉬운 문구 |
|:---|:---|
| `PaymentClient` | `Payment Service Fallback 실행: 주문ID=...` |
| `PaymentClient` | `Payment Fallback 응답 생성: 주문ID=..., 상태=PENDING` |
| `OrderServiceImpl` | `결제 보류: 주문ID=..., 상태=PENDING (Fallback)` |

**콘솔에서 직접 실행한 경우:** Order를 띄운 터미널에서 위 메시지 검색.

**Docker인 경우:**

```bash
docker compose -f docker-compose-msa.yml logs -f order-service
```

(또는 `docker logs -f order-service`)

로그 레벨이 `WARN`/`INFO` 이상이면 위 문구가 보입니다. Retry 때문에 **같은 요청에 대해 실패 로그가 여러 줄** 찍힐 수 있습니다.

#### (5) 실습 후 복구

Docker였다면:

```bash
docker compose -f docker-compose-msa.yml start payment-service
```

로컬이면 Payment 프로세스를 다시 기동합니다.

---

### 6.4 Circuit Breaker 상태 (`curl` 응답 읽는 법)

Order 서비스가 떠 있는 상태에서 실행합니다. (포트가 8080이 아니면 URL만 바꿉니다.)

```bash
curl http://localhost:8080/actuator/circuitbreakers
curl http://localhost:8080/actuator/circuitbreakerevents
```

PowerShell에서 본문만 예쁘게 보려면(선택):

```powershell
curl.exe -s http://localhost:8080/actuator/circuitbreakers | ConvertFrom-Json | ConvertTo-Json -Depth 10
```

#### 의미 있는 응답 받기 — 404·연결 실패 없이 호출하는 절차

아래를 순서대로 맞추면 **`HTTP 200`** 과 **`paymentService`** 가 담긴 JSON을 기대할 수 있습니다. (빈 이벤트 배열은 정상일 수 있음.)

**1. 먼저 “누가 8080을 듣는지” 확인**

| 증상 | 의미 | 조치 |
|:---|:---|:---|
| `curl: (7) Failed to connect ... 8080` | **리스너 없음** | Order 앱/컨테이너를 기동했는지, 포트가 8080인지 확인 |
| `404` + JSON에 `path`만 있음 | **엔드포인트 미노출 또는 Resilience4j 미등록** | 아래 3번 조건 확인 |
| `200` + `"circuitBreakers":{"paymentService":{...}}` | **정상** | 아래 “기대 응답” 참고 |

**2-A. 로컬에서 JVM으로 띄우는 경우**

1. 프로젝트 루트에서 `gradlew.bat bootRun`(또는 IDE에서 `MonolithToMsaApplication` 실행).
2. 프로필을 바꿨다면 **`order`** 등에서도 `application-*.yaml`에 `circuitbreakers` / `circuitbreakerevents` 노출이 있는지 확인.
3. 준비 확인:
   ```bash
   curl -s -o NUL -w "health HTTP:%{http_code}\n" http://localhost:8080/actuator/health
   ```
   - Windows CMD: `curl -s http://localhost:8080/actuator | findstr circuitbreaker`
   - PowerShell/Bash: `curl -s http://localhost:8080/actuator` 본문에 `"circuitbreakers"` 문자열이 포함되는지 확인  
   링크가 보이면 Actuator에 서킷 엔드포인트가 등록된 상태입니다.

**2-B. Docker Compose (`docker-compose-msa.yml`)로 띄우는 경우**

1. 호스트 **8080·8081** 이 비어 있는지 확인(이미 쓰이면 `Bind ... failed: port is already allocated` 로 컨테이너가 안 뜸).
2. 빌드 포함 기동:
   ```bash
   docker compose -f docker-compose-msa.yml up -d --build
   ```
3. **Payment가 healthy** 된 뒤 Order가 올라오므로, **30~60초 정도 대기** 후 호출하는 것이 안전합니다.
4. 상태 확인:
   ```bash
   docker compose -f docker-compose-msa.yml ps
   docker compose -f docker-compose-msa.yml logs --tail=50 order-service
   ```
5. 호스트에서 curl 대상은 **항상 Order 서비스** → `http://localhost:8080/actuator/...` (Payment는 8081).

**3. 404를 막는 설정·의존성 (이미 저장소에 반영된 전제)**

- `management.endpoints.web.exposure.include`에 **`circuitbreakers`**, **`circuitbreakerevents`** 포함 (`application.yaml` 또는 `application-order.yaml`).
- **`resilience4j.circuitbreaker.instances.paymentService`** 정의 + `build.gradle`의 **`resilience4j-spring-boot3` 2.3.0 이상** (Spring Boot 4에서 서킷 Actuator 연동용).
- `docker` 프로필 컨테이너는 **`application-docker.yaml`** 로 `SERVER_PORT`·바인드 주소가 맞는지 확인(Compose 예제와 동기화).

**4. 호출 예시 (HTTP 코드까지 보기)**

```bash
curl -s -w "\nHTTP:%{http_code}\n" http://localhost:8080/actuator/circuitbreakers
curl -s -w "\nHTTP:%{http_code}\n" http://localhost:8080/actuator/circuitbreakerevents
```

**5. 기대하는 “의미 있는” 본문 예시**

- **`/actuator/circuitbreakers`**: 최소한 **`paymentService`** 키와 **`state`**(예: `CLOSED`), 실패율·버퍼 관련 필드가 보이면 성공입니다.
- **`/actuator/circuitbreakerevents`**: 처음에는 **`circuitBreakerEvents": []`** 일 수 있습니다. **6.3처럼 Payment 장애를 유발**하거나 주문 생성으로 Payment를 여러 번 호출한 뒤 다시 조회하면 이벤트가 쌓일 수 있습니다.

---

#### `/actuator/circuitbreakers`

- **역할:** Resilience4j에 등록된 **서킷 브레이커 인스턴스별 현재 상태**를 JSON으로 돌려줍니다.
- **이 프로젝트에서 볼 이름:** YAML·코드와 같이 **`paymentService`** 인스턴스가 있어야 합니다.
- **상태 값 의미 (요지):**
  - **`CLOSED`**: 정상 — 원격 호출을 그대로 통과시킴.
  - **`OPEN`**: 차단 — 실패율 등이 임계값을 넘어 **빠르게 실패**시키는 구간(설정의 `waitDurationInOpenState` 동안 유지 후 HALF_OPEN으로 전이 시도).
  - **`HALF_OPEN`**: 시험 — 소수 호출만 허용해 복구 여부를 판별.
- 응답 JSON에는 인스턴스별로 `state`, 측정용 카운터·버퍼 정보 등이 붙는 경우가 많습니다. **6.3 장애 실습 후**에는 `paymentService`의 `state`가 **`OPEN` 또는 HALF_OPEN`** 으로 바뀐 것을 볼 수 있는 경우가 많습니다(호출 패턴·임계값에 따라 CLOSED에 머무를 수도 있음).

#### `/actuator/circuitbreakerevents`

- **역할:** 서킷이 **CLOSED → OPEN → HALF_OPEN** 처럼 **상태가 바뀐 이벤트** 기록을 반환합니다.
- **확인 포인트:** `type`(예: 상태 전이), `circuitBreakerName`이 **`paymentService`** 인 항목, 타임스탬프 등. 장애를 여러 번 유발한 뒤 호출하면 이벤트가 쌓여 있습니다.
- 이벤트 버퍼는 **크기 제한**이 있어 오래된 항목은 사라질 수 있습니다.

**참고:** 위 엔드포인트는 `management.endpoints.web.exposure.include`에 **`circuitbreakers`**, **`circuitbreakerevents`** 가 포함되어 있어야 합니다. **기본 프로필**은 `application.yaml`, Order 실습 프로필은 `application-order.yaml`을 확인합니다.

**`404 Not Found`일 때:** (1) 노출 목록에 위 ID가 빠져 있지 않은지 확인. (2) **Spring Boot 4**에서는 `resilience4j-spring-boot3` **2.1.x**만으로는 Actuator에 서킷 엔드포인트가 등록되지 않는 경우가 있어, **`2.3.0` 이상**(본 프로젝트 `build.gradle`과 동일)을 사용합니다. 401/403이면 보안 설정을 확인합니다.

---

## 주요 파일 역할 정리

| 파일 | 역할 |
|:---|:---|
| `build.gradle` | WebFlux, Resilience4j 의존성 |
| `application-order.yaml` | Payment URL, resilience4j, actuator |
| `PaymentWebClientConfig.java` | `paymentWebClient` 빈, Netty 타임아웃 |
| `PaymentClient.java` | HTTP 호출, Retry/CB/timeout, Fallback |
| `OrderServiceImpl.java` | 결제 결과에 따른 주문 상태 전이 |
| `OrderCreateRequest.java` | `paymentMethod` 검증 |

---

## 부록 A: RestTemplate vs WebClient (성능 관점)

- **블로킹**: 요청당 스레드 점유 → 동시성에 스레드 풀 크기 병목.
- **WebClient + 논블로킹 I/O**: 적은 스레드로 더 많은 동시 연결 처리 가능.  
  다만 **서블릿 핸들러에서 `.block()`** 하면 해당 요청 스레드는 여전히 블로킹되므로, “완전 논블로킹 엔드투엔드”는 별도 설계(비동기 MVC 등)가 필요합니다.

---

## 부록 B: 장애 처리 전략 요약

| 수단 | 역할 |
|:---|:---|
| Retry | 일시적 네트워크 오류 완화 |
| Circuit Breaker | 연쇄 장애·장기 실패 시 빠른 실패 |
| timeout | 무한 대기 방지 (코드 5초) |
| Fallback | 주문 유지 + 결제 PENDING |

---

## 부록 C: 실습 체크리스트

- [ ] `build.gradle`에 WebFlux·Resilience4j(reactor) 의존성
- [ ] `application-order.yaml`에 `paymentService` CB/Retry 및 actuator exposure
- [ ] `PaymentWebClientConfig`에 `paymentWebClient` 빈
- [ ] `PaymentClient`에서 연산자 순서·`onErrorResume` Fallback
- [ ] `OrderServiceImpl` + `OrderCreateRequest.paymentMethod`
- [ ] `./gradlew test` 통과
- [ ] (선택) Payment 중지 시 Fallback·CB 상태 확인

---

## 다음 단계 (참고)

1. 주문 생성과 결제를 이벤트/메시지로 비동기 분리  
2. API 게이트웨이·분산 추적  
3. 서비스 메시(mTLS, 재시도 정책 중앙화)
