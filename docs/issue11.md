# Ch06.11: 비동기 처리 실습 가이드

## 실습 목표

- Redis Pub/Sub으로 이벤트를 주고받는 흐름을 코드로 따라가기
- Publisher / Consumer 역할 분리와 비동기 결제 호출 이해
- DLQ(Dead Letter Queue)로 실패 메시지를 저장·재발행하는 패턴 이해

---

## 실습 순서 한눈에 보기

실제로 파일을 만들거나 수정할 때 아래 순서를 권장합니다.

| 순서 | 단계 | 주요 파일 |
|------|------|-----------|
| 1 | 이벤트·DLQ 도메인 | `domain/event/*.java` |
| 2 | DLQ 저장소 | `repository/event/DeadLetterMessageRepository.java` |
| 3 | Redis Pub/Sub 설정 | `config/RedisPubSubConfig.java`, `config/RedisListenerConfig.java`, `application-order.yaml`, `application-payment.yaml` |
| 4 | 이벤트 발행 | `service/event/EventPublisher.java` |
| 5 | Consumer·클라이언트 | `OrderCreatedEventListener.java`, `PaymentCompletedEventListener.java`, `client/PaymentClient.java` |
| 6 | DLQ 재처리·스케줄 | `DlqProcessor.java`, `MonolithToMsaApplication.java` |
| 7 | 비즈니스 연동 | `OrderServiceImpl.java`, `PaymentServiceImpl.java` |
| 8 | 실행·검증 | Redis 기동, 프로필, `curl` |

이후 섹션은 위 표와 같은 순서로 상세 설명합니다.

---

## 전체 구조 (요약)

### Redis Pub/Sub

- **Publisher**: `EventPublisher` — 채널 `order:created`, `payment:completed`로 JSON 문자열 발행
- **Subscriber**: `OrderCreatedEventListener`, `PaymentCompletedEventListener` — `MessageListener`로 직접 등록
- **직렬화**: 채널 페이로드는 **순수 JSON 문자열** (`StringRedisTemplate` + `ObjectMapper.writeValueAsString`). `RedisTemplate`에 JSON Serializer를 얹으면 문자열이 한 번 더 감싸져 Consumer의 `readValue`가 실패할 수 있음

### 이벤트 체인

1. 주문 생성 → `OrderCreatedEvent` 발행 → `OrderCreatedEventListener`가 수신 → `PaymentClient`로 비동기 결제
2. 결제 처리 완료/실패 → `PaymentCompletedEvent` 발행 → `PaymentCompletedEventListener`가 수신 → 주문 상태 `CONFIRMED` / `CANCELLED`

### DLQ

- 파싱·처리 중 예외 시 `DeadLetterMessage`를 DB에 저장
- `DlqProcessor`가 1분마다 `PENDING`이면서 재시도 횟수가 상한(예: 3) 미만인 메시지를 읽어 **동일 채널로 재발행**
- 재처리 성공 시 `PROCESSED`, 반복 실패 시 `FAILED`

---

## 핵심 메시지

- Pub/Sub은 **발행 후 즉시 전달**되며, 구독자가 없으면 메시지는 버려짐(영속 큐 아님)
- **순서 보장 없음** — 순서가 중요하면 Stream/Kafka 등 검토
- Order·Payment를 **별 프로세스**로 띄울 때는 **같은 Redis 인스턴스**를 바라봐야 이벤트가 전달됨

---

## 1단계: 이벤트·DLQ 도메인 모델

**디렉터리:** `src/main/java/com/ccommit/monolith_to_msa/domain/event/`

| 파일 | 역할 |
|------|------|
| `OrderCreatedEvent.java` | 주문 생성 직후 Redis로 보낼 페이로드 (주문 ID, 고객·상품·수량·금액·결제수단 문자열 등) |
| `PaymentCompletedEvent.java` | 결제 결과를 Order 쪽으로 알릴 페이로드 (결제 ID, 주문 ID, 금액, 거래 ID, 상태 문자열 등) |
| `DeadLetterMessage.java` | DLQ용 JPA 엔티티 — 채널명, 원본 JSON, 에러 메시지, 스택, 재시도 횟수, 상태(`PENDING`/`PROCESSED`/`FAILED`) |

**실습 포인트**

- 이벤트는 **직렬화 가능한 필드**만 두고, Redis에 실을 내용과 1:1에 가깝게 유지
- `DeadLetterMessage`는 리스너·`DlqProcessor`가 공통으로 사용하므로 **2단계 이전**에 정의하는 것이 자연스러움

### 이 단계에서 추가하는 코드 (전체)

#### `domain/event/OrderCreatedEvent.java`

```java
package com.ccommit.monolith_to_msa.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 주문 생성 이벤트
 * Redis Pub/Sub을 통해 발행되는 이벤트
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Long orderId;
    private String customerId;
    private String productId;
    private Integer quantity;
    private Long totalPrice;
    private String paymentMethod;
    private LocalDateTime createdAt;
    
    public static OrderCreatedEvent of(Long orderId, String customerId, String productId, 
                                      Integer quantity, Long totalPrice, String paymentMethod) {
        return OrderCreatedEvent.builder()
                .orderId(orderId)
                .customerId(customerId)
                .productId(productId)
                .quantity(quantity)
                .totalPrice(totalPrice)
                .paymentMethod(paymentMethod)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
```

#### `domain/event/PaymentCompletedEvent.java`

```java
package com.ccommit.monolith_to_msa.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 결제 완료 이벤트
 * Redis Pub/Sub을 통해 발행되는 이벤트
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCompletedEvent implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Long paymentId;
    private Long orderId;
    private Long amount;
    private String transactionId;
    private String status;
    private LocalDateTime completedAt;
    
    public static PaymentCompletedEvent of(Long paymentId, Long orderId, Long amount, 
                                           String transactionId, String status) {
        return PaymentCompletedEvent.builder()
                .paymentId(paymentId)
                .orderId(orderId)
                .amount(amount)
                .transactionId(transactionId)
                .status(status)
                .completedAt(LocalDateTime.now())
                .build();
    }
}
```

#### `domain/event/DeadLetterMessage.java`

```java
package com.ccommit.monolith_to_msa.domain.event;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Dead Letter Queue 엔티티
 * 처리 실패한 메시지를 저장하여 재처리 가능하도록 함
 */
@Entity
@Table(name = "dead_letter_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeadLetterMessage {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, length = 100)
    private String channel;  // Redis 채널명
    
    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;  // 원본 메시지 (JSON)
    
    @Column(nullable = false, length = 500)
    private String errorMessage;  // 에러 메시지
    
    @Column(columnDefinition = "TEXT")
    private String stackTrace;  // 스택 트레이스
    
    @Column(nullable = false)
    private Integer retryCount = 0;  // 재시도 횟수
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageStatus status = MessageStatus.PENDING;  // PENDING, PROCESSED, FAILED
    
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    private LocalDateTime processedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
    
    @Builder
    public DeadLetterMessage(String channel, String message, String errorMessage, 
                            String stackTrace, Integer retryCount, MessageStatus status) {
        this.channel = channel;
        this.message = message;
        this.errorMessage = errorMessage;
        this.stackTrace = stackTrace;
        this.retryCount = retryCount != null ? retryCount : 0;
        this.status = status != null ? status : MessageStatus.PENDING;
    }
    
    public void incrementRetryCount() {
        this.retryCount++;
    }
    
    public void markAsProcessed() {
        this.status = MessageStatus.PROCESSED;
        this.processedAt = LocalDateTime.now();
    }
    
    public void markAsFailed() {
        this.status = MessageStatus.FAILED;
    }
    
    public enum MessageStatus {
        PENDING,    // 재처리 대기
        PROCESSED,  // 재처리 완료
        FAILED      // 재처리 실패 (최대 재시도 초과)
    }
}
```

---

## 2단계: DLQ Repository

**파일:** `src/main/java/com/ccommit/monolith_to_msa/repository/event/DeadLetterMessageRepository.java`

- `JpaRepository<DeadLetterMessage, Long>` 상속
- `DlqProcessor`에서 사용: `findByStatusAndRetryCountLessThan(PENDING, max)` 형태로 **재처리 대상** 조회

**실습 포인트**

- DLQ는 **같은 애플리케이션 DB(H2)** 에 쌓임. Order 전용 프로필만 켜도 리스너·스케줄러가 동작하면 테이블이 생성됨

### 이 단계에서 추가하는 코드 (전체)

#### `repository/event/DeadLetterMessageRepository.java`

```java
package com.ccommit.monolith_to_msa.repository.event;

import com.ccommit.monolith_to_msa.domain.event.DeadLetterMessage;
import com.ccommit.monolith_to_msa.domain.event.DeadLetterMessage.MessageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Dead Letter Queue Repository
 */
@Repository
public interface DeadLetterMessageRepository extends JpaRepository<DeadLetterMessage, Long> {
    
    List<DeadLetterMessage> findByStatus(MessageStatus status);
    
    List<DeadLetterMessage> findByStatusAndRetryCountLessThan(MessageStatus status, Integer maxRetryCount);
    
    List<DeadLetterMessage> findByChannel(String channel);
}
```

---

## 3단계: Redis Pub/Sub 설정

### 3-1. `RedisPubSubConfig.java`

- **`StringRedisTemplate` 빈**: `convertAndSend(channel, jsonString)` 용
- **`RedisMessageListenerContainer`**: 구독 연결 관리
- **`ChannelTopic` 빈 두 개** 또는 상수: `order:created`, `payment:completed`
- 상수는 `ORDER_CREATED_CHANNEL`, `PAYMENT_COMPLETED_CHANNEL` 로 통일해 Publisher·Listener·DlqProcessor에서 재사용

### 3-2. `RedisListenerConfig.java`

- `@PostConstruct`에서 `container.addMessageListener(listener, topic)` 호출
- 리스너는 **`org.springframework.data.redis.connection.MessageListener` 구현체**를 그대로 등록 (Spring Data Redis 4.x에서 `MessageListenerAdapter` + 메서드 이름 조합 시 `invoker` NPE가 나는 경우가 있어, 본 프로젝트는 직접 등록)
- 마지막에 `redisMessageListenerContainer.start()` 호출

### 3-3. YAML

- **`application-order.yaml`**: `spring.data.redis` (host/port/timeout/pool), `payment.service.url` (Payment API 베이스 URL)
- **`application-payment.yaml`**: 동일하게 `spring.data.redis` — Payment 프로세스에서 `PaymentCompletedEvent`를 발행하므로 **Order와 같은 Redis**가 필요

**실습 포인트**

- Redis 미기동 시 빈 생성/연결 단계에서 실패할 수 있음 — **8단계 전에 Redis 기동** 확인

### 이 단계에서 추가하는 코드 (전체)

#### `config/RedisPubSubConfig.java`

```java
package com.ccommit.monolith_to_msa.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub 설정
 * <p>채널 메시지는 JSON 문자열 그대로 전달하기 위해 {@link StringRedisTemplate} 사용
 * (RedisTemplate + JSON 직렬화 시 문자열이 한 번 더 따옴표로 감싸져 Consumer 파싱이 실패할 수 있음)
 */
@Configuration
@Slf4j
public class RedisPubSubConfig {
    
    public static final String ORDER_CREATED_CHANNEL = "order:created";
    public static final String PAYMENT_COMPLETED_CHANNEL = "payment:completed";
    
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
    
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }
    
    @Bean
    public ChannelTopic orderCreatedTopic() {
        return new ChannelTopic(ORDER_CREATED_CHANNEL);
    }
    
    @Bean
    public ChannelTopic paymentCompletedTopic() {
        return new ChannelTopic(PAYMENT_COMPLETED_CHANNEL);
    }
}
```

#### `config/RedisListenerConfig.java`

```java
package com.ccommit.monolith_to_msa.config;

import com.ccommit.monolith_to_msa.service.event.OrderCreatedEventListener;
import com.ccommit.monolith_to_msa.service.event.PaymentCompletedEventListener;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub 리스너 등록 설정
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class RedisListenerConfig {
    
    private final OrderCreatedEventListener orderCreatedEventListener;
    private final PaymentCompletedEventListener paymentCompletedEventListener;
    private final RedisMessageListenerContainer redisMessageListenerContainer;
    
    @PostConstruct
    public void registerListeners() {
        log.info("Redis Pub/Sub 리스너 등록 시작");
        
        // 리스너는 MessageListener 구현체로 직접 등록 (Spring Data Redis 4.x에서 MessageListenerAdapter + 메서드명 조합 시 invoker NPE 방지)
        redisMessageListenerContainer.addMessageListener(
                orderCreatedEventListener,
                new ChannelTopic(RedisPubSubConfig.ORDER_CREATED_CHANNEL)
        );
        log.info("주문 생성 이벤트 리스너 등록 완료: 채널={}", RedisPubSubConfig.ORDER_CREATED_CHANNEL);
        
        redisMessageListenerContainer.addMessageListener(
                paymentCompletedEventListener,
                new ChannelTopic(RedisPubSubConfig.PAYMENT_COMPLETED_CHANNEL)
        );
        log.info("결제 완료 이벤트 리스너 등록 완료: 채널={}", RedisPubSubConfig.PAYMENT_COMPLETED_CHANNEL);
        
        // 컨테이너 시작
        redisMessageListenerContainer.start();
        log.info("Redis Pub/Sub 리스너 컨테이너 시작 완료");
    }
}
```

#### `src/main/resources/application-order.yaml`

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
  
  # Redis 설정 (Pub/Sub)
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 2000ms
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 0

# Payment Service URL (환경 변수로 주입 가능)
payment:
  service:
    url: ${PAYMENT_SERVICE_URL:http://localhost:8081}

# Logging 설정
logging:
  level:
    io.netty.resolver.dns.DnsServerAddressStreamProviders: OFF  # Netty DNS 경고 완전히 숨김
    io.netty.resolver.dns: OFF  # Netty DNS 관련 모든 경고 숨김

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

#### `src/main/resources/application-payment.yaml`

```yaml
# Payment Service 설정 (실습용 예제)
# 실제 서비스 분리 시 별도 프로젝트의 application.yaml로 구성

server:
  port: 8081
  error:
    include-message: always
    include-binding-errors: always

spring:
  application:
    name: payment-service
  
  # Database 설정 (Payment DB)
  datasource:
    url: jdbc:h2:mem:paymentdb
    driver-class-name: org.h2.Driver
    username: sa
    password: sa
    # 독립적인 커넥션 풀 (Payment Service 전용)
    hikari:
      maximum-pool-size: 20  # 결제 서비스 전용 커넥션 풀
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

  # Redis (결제 완료 이벤트 Pub/Sub — Order Consumer와 동일 브로커 필요)
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 2000ms
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 0

# Actuator 설정
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
  metrics:
    export:
      prometheus:
        enabled: true
```

---

## 4단계: 이벤트 Publisher

**파일:** `service/event/EventPublisher.java`

- 생성자에서 `StringRedisTemplate` 주입, 내부 `ObjectMapper`에 `JavaTimeModule` 등록(시간 타입 직렬화)
- `publishOrderCreated` / `publishPaymentCompleted`: DTO → JSON 문자열 → `stringRedisTemplate.convertAndSend(채널, message)`
- JSON 직렬화 실패 시 로그 후 런타임 예외

**실습 포인트**

- 발행과 구독 **양쪽 모두 동일한 JSON 스키마**를 가정 — 필드명·타입 변경 시 Consumer와 함께 수정

### 이 단계에서 추가하는 코드 (전체)

#### `service/event/EventPublisher.java`

```java
package com.ccommit.monolith_to_msa.service.event;

import com.ccommit.monolith_to_msa.domain.event.OrderCreatedEvent;
import com.ccommit.monolith_to_msa.domain.event.PaymentCompletedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import static com.ccommit.monolith_to_msa.config.RedisPubSubConfig.ORDER_CREATED_CHANNEL;
import static com.ccommit.monolith_to_msa.config.RedisPubSubConfig.PAYMENT_COMPLETED_CHANNEL;

/**
 * Redis Pub/Sub 이벤트 발행자
 */
@Service
@Slf4j
public class EventPublisher {
    
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    
    public EventPublisher(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    /**
     * 주문 생성 이벤트 발행
     */
    public void publishOrderCreated(OrderCreatedEvent event) {
        try {
            String message = objectMapper.writeValueAsString(event);
            stringRedisTemplate.convertAndSend(ORDER_CREATED_CHANNEL, message);
            log.info("주문 생성 이벤트 발행: 주문ID={}, 채널={}", event.getOrderId(), ORDER_CREATED_CHANNEL);
        } catch (JsonProcessingException e) {
            log.error("주문 생성 이벤트 발행 실패: 주문ID={}, 오류={}", event.getOrderId(), e.getMessage(), e);
            throw new RuntimeException("이벤트 발행 실패", e);
        }
    }
    
    /**
     * 결제 완료 이벤트 발행
     */
    public void publishPaymentCompleted(PaymentCompletedEvent event) {
        try {
            String message = objectMapper.writeValueAsString(event);
            stringRedisTemplate.convertAndSend(PAYMENT_COMPLETED_CHANNEL, message);
            log.info("결제 완료 이벤트 발행: 결제ID={}, 주문ID={}, 채널={}", 
                    event.getPaymentId(), event.getOrderId(), PAYMENT_COMPLETED_CHANNEL);
        } catch (JsonProcessingException e) {
            log.error("결제 완료 이벤트 발행 실패: 결제ID={}, 오류={}", event.getPaymentId(), e.getMessage(), e);
            throw new RuntimeException("이벤트 발행 실패", e);
        }
    }
}
```

---

## 5단계: 비동기 Consumer (리스너) · `PaymentClient`

### `OrderCreatedEventListener.java`

- `implements MessageListener` — `onMessage(Message message, byte[] pattern)`
- 채널 문자열이 `order:created`인지 확인 후 `ObjectMapper.readValue`로 `OrderCreatedEvent` 복원
- **`Optional<PaymentClient> paymentClient`**: `payment` 프로필 등에서 빈이 없으면 `null`로 두고 결제 호출을 건너뜀(Order만 띄운 실습 시 유용)
- 있으면 `PaymentCreateRequest` 조립 후 `paymentClient.processPayment(...).subscribe(...)` — **WebFlux 비동기**; Redis 스레드에서 블로킹하지 않음
- `onMessage` 전체를 try/catch로 감싸 **파싱·동기 구간 예외**는 `saveToDLQ` 호출 (비동기 `subscribe`의 `onError`는 별도로 DLQ에 넣지 않을 수 있음 — 현재 코드 기준)

### `PaymentCompletedEventListener.java`

- `payment:completed` 채널 처리, `PaymentCompletedEvent` → `OrderRepository`로 주문 조회 후 상태 갱신
- `PaymentStatus.COMPLETED` → 주문 `CONFIRMED`, `FAILED` → `cancel()` 등
- `@Transactional` on `onMessage` — DB 업데이트 단위 트랜잭션
- 실패 시 DLQ 저장 패턴은 주문 리스너와 동일

**실습 포인트**

- **Order(8080) + Payment(8081) + Redis** 를 함께 띄워야 끝까지 흐름이 이어짐
- Payment만 단독으로 띄우면 `OrderCreated`를 소비할 Consumer가 없을 수 있음(구조에 따라 다름). 본 샘플은 **한 코드베이스·프로필 분리**이므로 Order 프로필 인스턴스가 Consumer 역할

### 이 단계에서 추가하는 코드 (전체)

#### `service/event/OrderCreatedEventListener.java`

```java
package com.ccommit.monolith_to_msa.service.event;

import com.ccommit.monolith_to_msa.client.PaymentClient;
import com.ccommit.monolith_to_msa.domain.event.DeadLetterMessage;
import com.ccommit.monolith_to_msa.domain.event.OrderCreatedEvent;
import com.ccommit.monolith_to_msa.domain.payment.PaymentMethod;
import com.ccommit.monolith_to_msa.dto.payment.PaymentCreateRequest;
import com.ccommit.monolith_to_msa.repository.event.DeadLetterMessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;

import static com.ccommit.monolith_to_msa.config.RedisPubSubConfig.ORDER_CREATED_CHANNEL;

/**
 * 주문 생성 이벤트 리스너 (비동기 Consumer)
 * Redis Pub/Sub을 통해 주문 생성 이벤트를 구독하고 결제 처리를 비동기로 수행
 */
@Component
@Slf4j
public class OrderCreatedEventListener implements MessageListener {
    
    private final PaymentClient paymentClient;
    private final DeadLetterMessageRepository dlqRepository;
    private final ObjectMapper objectMapper;
    private static final int MAX_RETRY_COUNT = 3;
    
    public OrderCreatedEventListener(
            Optional<PaymentClient> paymentClient,
            DeadLetterMessageRepository dlqRepository) {
        this.paymentClient = paymentClient.orElse(null);
        this.dlqRepository = dlqRepository;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel());
        String body = new String(message.getBody());
        
        log.info("이벤트 수신: 채널={}, 메시지={}", channel, body);
        
        if (!ORDER_CREATED_CHANNEL.equals(channel)) {
            log.warn("알 수 없는 채널: {}", channel);
            return;
        }
        
        try {
            // JSON 메시지를 OrderCreatedEvent로 변환
            OrderCreatedEvent event = objectMapper.readValue(body, OrderCreatedEvent.class);
            log.info("주문 생성 이벤트 처리 시작: 주문ID={}", event.getOrderId());
            
            // 결제 처리 (비동기)
            processPayment(event);
            
        } catch (Exception e) {
            log.error("주문 생성 이벤트 처리 실패: 메시지={}, 오류={}", body, e.getMessage(), e);
            
            // DLQ에 저장
            saveToDLQ(ORDER_CREATED_CHANNEL, body, e);
        }
    }
    
    /**
     * 결제 처리 (비동기)
     */
    private void processPayment(OrderCreatedEvent event) {
        if (paymentClient == null) {
            log.warn("PaymentClient가 없습니다. 결제 처리를 건너뜁니다: 주문ID={}", event.getOrderId());
            return;
        }
        
        try {
            // String을 PaymentMethod enum으로 변환
            PaymentMethod paymentMethod = event.getPaymentMethod() != null 
                    ? PaymentMethod.valueOf(event.getPaymentMethod()) 
                    : PaymentMethod.CREDIT_CARD;
            
            PaymentCreateRequest paymentRequest = PaymentCreateRequest.builder()
                    .orderId(event.getOrderId())
                    .amount(event.getTotalPrice())
                    .method(paymentMethod)
                    .build();
            
            // 비동기 결제 처리 (Non-blocking)
            paymentClient.processPayment(paymentRequest)
                    .subscribe(
                            paymentResponse -> {
                                log.info("비동기 결제 처리 완료: 주문ID={}, 결제ID={}, 상태={}", 
                                        event.getOrderId(), paymentResponse.getId(), paymentResponse.getStatus());
                            },
                            error -> {
                                log.error("비동기 결제 처리 실패: 주문ID={}, 오류={}", 
                                        event.getOrderId(), error.getMessage(), error);
                            }
                    );
            
        } catch (Exception e) {
            log.error("결제 처리 중 오류 발생: 주문ID={}, 오류={}", event.getOrderId(), e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * DLQ에 메시지 저장
     */
    private void saveToDLQ(String channel, String message, Exception error) {
        try {
            String errorMessage = error.getMessage();
            String stackTrace = getStackTrace(error);
            
            DeadLetterMessage dlqMessage = DeadLetterMessage.builder()
                    .channel(channel)
                    .message(message)
                    .errorMessage(errorMessage)
                    .stackTrace(stackTrace)
                    .retryCount(0)
                    .status(DeadLetterMessage.MessageStatus.PENDING)
                    .build();
            
            dlqRepository.save(dlqMessage);
            log.info("DLQ에 메시지 저장: 채널={}, 메시지ID={}", channel, dlqMessage.getId());
            
        } catch (Exception e) {
            log.error("DLQ 저장 실패: 채널={}, 오류={}", channel, e.getMessage(), e);
        }
    }
    
    private String getStackTrace(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }
}
```

#### `service/event/PaymentCompletedEventListener.java`

```java
package com.ccommit.monolith_to_msa.service.event;

import com.ccommit.monolith_to_msa.domain.event.DeadLetterMessage;
import com.ccommit.monolith_to_msa.domain.event.PaymentCompletedEvent;
import com.ccommit.monolith_to_msa.domain.order.Order;
import com.ccommit.monolith_to_msa.domain.order.OrderStatus;
import com.ccommit.monolith_to_msa.domain.payment.PaymentStatus;
import com.ccommit.monolith_to_msa.repository.event.DeadLetterMessageRepository;
import com.ccommit.monolith_to_msa.repository.order.OrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.PrintWriter;
import java.io.StringWriter;

import static com.ccommit.monolith_to_msa.config.RedisPubSubConfig.PAYMENT_COMPLETED_CHANNEL;

/**
 * 결제 완료 이벤트 리스너 (비동기 Consumer)
 * Redis Pub/Sub을 통해 결제 완료 이벤트를 구독하고 주문 상태를 업데이트
 */
@Component
@Slf4j
public class PaymentCompletedEventListener implements MessageListener {
    
    private final OrderRepository orderRepository;
    private final DeadLetterMessageRepository dlqRepository;
    private final ObjectMapper objectMapper;
    
    public PaymentCompletedEventListener(
            OrderRepository orderRepository,
            DeadLetterMessageRepository dlqRepository) {
        this.orderRepository = orderRepository;
        this.dlqRepository = dlqRepository;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    @Transactional
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel());
        String body = new String(message.getBody());
        
        log.info("이벤트 수신: 채널={}, 메시지={}", channel, body);
        
        if (!PAYMENT_COMPLETED_CHANNEL.equals(channel)) {
            log.warn("알 수 없는 채널: {}", channel);
            return;
        }
        
        try {
            // JSON 메시지를 PaymentCompletedEvent로 변환
            PaymentCompletedEvent event = objectMapper.readValue(body, PaymentCompletedEvent.class);
            log.info("결제 완료 이벤트 처리 시작: 결제ID={}, 주문ID={}", event.getPaymentId(), event.getOrderId());
            
            // 주문 상태 업데이트
            updateOrderStatus(event);
            
        } catch (Exception e) {
            log.error("결제 완료 이벤트 처리 실패: 메시지={}, 오류={}", body, e.getMessage(), e);
            
            // DLQ에 저장
            saveToDLQ(PAYMENT_COMPLETED_CHANNEL, body, e);
        }
    }
    
    /**
     * 주문 상태 업데이트
     */
    private void updateOrderStatus(PaymentCompletedEvent event) {
        Order order = orderRepository.findById(event.getOrderId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "주문을 찾을 수 없습니다: " + event.getOrderId()));
        
        if (PaymentStatus.COMPLETED.name().equals(event.getStatus())) {
            // 결제 완료: 주문 상태를 CONFIRMED로 변경
            order.updateStatus(OrderStatus.CONFIRMED);
            orderRepository.save(order);
            log.info("주문 상태 업데이트 완료: 주문ID={}, 상태=CONFIRMED", event.getOrderId());
        } else if (PaymentStatus.FAILED.name().equals(event.getStatus())) {
            // 결제 실패: 주문 취소
            order.cancel();
            orderRepository.save(order);
            log.info("주문 취소 완료: 주문ID={}, 상태=CANCELLED", event.getOrderId());
        }
    }
    
    /**
     * DLQ에 메시지 저장
     */
    private void saveToDLQ(String channel, String message, Exception error) {
        try {
            String errorMessage = error.getMessage();
            String stackTrace = getStackTrace(error);
            
            DeadLetterMessage dlqMessage = DeadLetterMessage.builder()
                    .channel(channel)
                    .message(message)
                    .errorMessage(errorMessage)
                    .stackTrace(stackTrace)
                    .retryCount(0)
                    .status(DeadLetterMessage.MessageStatus.PENDING)
                    .build();
            
            dlqRepository.save(dlqMessage);
            log.info("DLQ에 메시지 저장: 채널={}, 메시지ID={}", channel, dlqMessage.getId());
            
        } catch (Exception e) {
            log.error("DLQ 저장 실패: 채널={}, 오류={}", channel, e.getMessage(), e);
        }
    }
    
    private String getStackTrace(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }
}
```

#### `client/PaymentClient.java`

```java
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
```

---

## 6단계: DLQ 재처리 및 스케줄링

### `DlqProcessor.java`

- `@Scheduled(fixedDelay = 60000)` — 약 1분 간격
- `processDlqMessages`: `PENDING`이고 `retryCount`가 최대 재시도 미만인 목록 조회
- `retryMessage`: 채널에 따라 JSON을 `OrderCreatedEvent` / `PaymentCompletedEvent`로 읽어 `eventPublisher`로 **재발행**
- 재발행까지 성공하면 `markAsProcessed()` 후 저장
- `retryMessage`에서 예외 시 루프에서 `incrementRetryCount`, `retryCount >= 3`이면 `markAsFailed()`

### `MonolithToMsaApplication.java`

- `@EnableScheduling` — 위 스케줄러 활성화

**실습 포인트**

- DLQ 재처리는 **메시지를 Redis에 다시 넣는 것**이므로, 그때도 Consumer가 살아 있어야 실제 복구가 이어짐

### 이 단계에서 추가하는 코드 (전체)

#### `service/event/DlqProcessor.java`

```java
package com.ccommit.monolith_to_msa.service.event;

import com.ccommit.monolith_to_msa.domain.event.DeadLetterMessage;
import com.ccommit.monolith_to_msa.domain.event.OrderCreatedEvent;
import com.ccommit.monolith_to_msa.domain.event.PaymentCompletedEvent;
import com.ccommit.monolith_to_msa.repository.event.DeadLetterMessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.ccommit.monolith_to_msa.config.RedisPubSubConfig.ORDER_CREATED_CHANNEL;
import static com.ccommit.monolith_to_msa.config.RedisPubSubConfig.PAYMENT_COMPLETED_CHANNEL;

/**
 * Dead Letter Queue 처리 서비스
 * 주기적으로 DLQ의 메시지를 재처리
 */
@Service
@Slf4j
public class DlqProcessor {
    
    private final DeadLetterMessageRepository dlqRepository;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private static final int MAX_RETRY_COUNT = 3;
    
    public DlqProcessor(
            DeadLetterMessageRepository dlqRepository,
            EventPublisher eventPublisher) {
        this.dlqRepository = dlqRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    /**
     * DLQ 메시지 재처리 (스케줄러: 1분마다 실행)
     */
    @Scheduled(fixedDelay = 60000) // 1분
    @Transactional
    public void processDlqMessages() {
        List<DeadLetterMessage> pendingMessages = dlqRepository
                .findByStatusAndRetryCountLessThan(
                        DeadLetterMessage.MessageStatus.PENDING, 
                        MAX_RETRY_COUNT);
        
        if (pendingMessages.isEmpty()) {
            return;
        }
        
        log.info("DLQ 메시지 재처리 시작: 건수={}", pendingMessages.size());
        
        for (DeadLetterMessage dlqMessage : pendingMessages) {
            try {
                retryMessage(dlqMessage);
            } catch (Exception e) {
                log.error("DLQ 메시지 재처리 실패: 메시지ID={}, 오류={}", 
                        dlqMessage.getId(), e.getMessage(), e);
                
                // 재시도 횟수 증가
                dlqMessage.incrementRetryCount();
                
                // 최대 재시도 횟수 초과 시 FAILED 상태로 변경
                if (dlqMessage.getRetryCount() >= MAX_RETRY_COUNT) {
                    dlqMessage.markAsFailed();
                    log.error("DLQ 메시지 재처리 최대 횟수 초과: 메시지ID={}, 재시도횟수={}", 
                            dlqMessage.getId(), dlqMessage.getRetryCount());
                }
                
                dlqRepository.save(dlqMessage);
            }
        }
    }
    
    /**
     * DLQ 메시지 재처리
     */
    private void retryMessage(DeadLetterMessage dlqMessage) throws Exception {
        log.info("DLQ 메시지 재처리 시도: 메시지ID={}, 채널={}, 재시도횟수={}", 
                dlqMessage.getId(), dlqMessage.getChannel(), dlqMessage.getRetryCount());
        
        String channel = dlqMessage.getChannel();
        String message = dlqMessage.getMessage();
        
        // 채널에 따라 이벤트 재발행
        if (ORDER_CREATED_CHANNEL.equals(channel)) {
            OrderCreatedEvent event = objectMapper.readValue(message, OrderCreatedEvent.class);
            eventPublisher.publishOrderCreated(event);
            
        } else if (PAYMENT_COMPLETED_CHANNEL.equals(channel)) {
            PaymentCompletedEvent event = objectMapper.readValue(message, PaymentCompletedEvent.class);
            eventPublisher.publishPaymentCompleted(event);
            
        } else {
            throw new IllegalArgumentException("알 수 없는 채널: " + channel);
        }
        
        // 재처리 성공: PROCESSED 상태로 변경
        dlqMessage.markAsProcessed();
        dlqRepository.save(dlqMessage);
        
        log.info("DLQ 메시지 재처리 성공: 메시지ID={}", dlqMessage.getId());
    }
}
```

#### `com/ccommit/monolith_to_msa/MonolithToMsaApplication.java`

```java
package com.ccommit.monolith_to_msa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MonolithToMsaApplication {

	public static void main(String[] args) {
		SpringApplication.run(MonolithToMsaApplication.class, args);
	}

}
```

---

## 7단계: 서비스 계층 연동

### `OrderServiceImpl.java`

- `createOrder` 마지막에 `OrderCreatedEvent.of(...)` 생성 후 `eventPublisher.publishOrderCreated(event)`
- **동기 `PaymentClient` 호출은 제거** — 응답은 주문 저장 직후 바로 반환(결제는 비동기 파이프라인)

### `PaymentServiceImpl.java`

- 결제 성공/실패 처리 후 `PaymentCompletedEvent` 빌드 → `eventPublisher.publishPaymentCompleted(event)`

- (`PaymentClient`는 **5단계** 코드에 포함 — 리스너에서 비동기 호출)

**실습 포인트**

- API 요청 바디의 `paymentMethod`는 검증 필수(예: `CREDIT_CARD`) — 누락 시 400

### 이 단계에서 추가하는 코드 (전체)

#### `service/order/OrderServiceImpl.java`

```java
package com.ccommit.monolith_to_msa.service.order;

import com.ccommit.monolith_to_msa.domain.event.OrderCreatedEvent;
import com.ccommit.monolith_to_msa.domain.order.Order;
import com.ccommit.monolith_to_msa.domain.order.OrderStatus;
import com.ccommit.monolith_to_msa.domain.product.Product;
import com.ccommit.monolith_to_msa.dto.order.OrderCreateRequest;
import com.ccommit.monolith_to_msa.dto.order.OrderResponse;
import com.ccommit.monolith_to_msa.exception.InsufficientStockException;
import com.ccommit.monolith_to_msa.exception.ProductNotFoundException;
import com.ccommit.monolith_to_msa.repository.order.OrderRepository;
import com.ccommit.monolith_to_msa.repository.product.ProductRepository;
import com.ccommit.monolith_to_msa.service.event.EventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Order 서비스 구현체
 * Repository 인터페이스에 의존 (DIP 적용)
 * 결제는 Redis 이벤트(`OrderCreated`) 후 비동기 Consumer·Payment API로 처리
 */
@Service
@Transactional(readOnly = true)
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final EventPublisher eventPublisher;
    
    @Autowired
    public OrderServiceImpl(
            OrderRepository orderRepository,
            ProductRepository productRepository,
            EventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.eventPublisher = eventPublisher;
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

        // 6. 주문 생성 이벤트 발행 (Redis Pub/Sub)
        // 이벤트 기반 아키텍처: 결제 처리는 비동기 Consumer가 처리
        OrderCreatedEvent event = OrderCreatedEvent.of(
                savedOrder.getId(),
                savedOrder.getCustomerId(),
                savedOrder.getProductId(),
                savedOrder.getQuantity(),
                savedOrder.getTotalPrice(),
                request.getPaymentMethod() != null ? request.getPaymentMethod().name() : null
        );
        eventPublisher.publishOrderCreated(event);
        log.info("주문 생성 이벤트 발행 완료: 주문ID={}", savedOrder.getId());
        
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

#### `service/payment/PaymentServiceImpl.java`

```java
package com.ccommit.monolith_to_msa.service.payment;

import com.ccommit.monolith_to_msa.domain.event.PaymentCompletedEvent;
import com.ccommit.monolith_to_msa.domain.order.Order;
import com.ccommit.monolith_to_msa.domain.payment.Payment;
import com.ccommit.monolith_to_msa.domain.payment.PaymentStatus;
import com.ccommit.monolith_to_msa.dto.payment.PaymentCreateRequest;
import com.ccommit.monolith_to_msa.dto.payment.PaymentResponse;
import com.ccommit.monolith_to_msa.exception.OrderException;
import com.ccommit.monolith_to_msa.repository.order.OrderRepository;
import com.ccommit.monolith_to_msa.repository.payment.PaymentRepository;
import com.ccommit.monolith_to_msa.service.event.EventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment 서비스 구현체
 * 트랜잭션 관리 및 재시도 로직 포함
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class PaymentServiceImpl implements PaymentService {
    
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final PaymentGatewayService paymentGatewayService;
    private final EventPublisher eventPublisher;
    
    @Override
    @Transactional
    @Retryable(
        retryFor = PaymentGatewayException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public PaymentResponse processPayment(PaymentCreateRequest request) {
        log.info("결제 처리 시작: 주문ID={}, 금액={}", request.getOrderId(), request.getAmount());
        
        // 1. 주문 조회
        Order order = orderRepository.findById(request.getOrderId())
                .orElseThrow(() -> new OrderException("주문을 찾을 수 없습니다: " + request.getOrderId()));
        
        // 2. 결제 엔티티 생성 (PENDING 상태) - 별도 트랜잭션으로 저장
        Payment savedPayment = savePaymentEntity(order, request);
        log.info("결제 엔티티 생성: 결제ID={}, 상태={}", savedPayment.getId(), savedPayment.getStatus());
        
        try {
            // 3. PG사 결제 요청 (재시도 가능)
            String transactionId = paymentGatewayService.requestPayment(
                    request.getAmount(),
                    request.getMethod()
            );
            
            // 4. 결제 완료 처리
            Payment completedPayment = updatePaymentStatus(savedPayment.getId(), PaymentStatus.COMPLETED, transactionId);
            
            log.info("결제 완료: 결제ID={}, 거래ID={}", completedPayment.getId(), transactionId);
            
            // 5. 결제 완료 이벤트 발행 (Redis Pub/Sub)
            PaymentCompletedEvent event = PaymentCompletedEvent.of(
                    completedPayment.getId(),
                    completedPayment.getOrder().getId(),
                    completedPayment.getAmount(),
                    completedPayment.getTransactionId(),
                    completedPayment.getStatus().name()
            );
            eventPublisher.publishPaymentCompleted(event);
            log.info("결제 완료 이벤트 발행 완료: 결제ID={}, 주문ID={}", completedPayment.getId(), completedPayment.getOrder().getId());
            
            return PaymentResponse.from(completedPayment);
            
        } catch (PaymentGatewayException e) {
            // 5. 결제 실패 처리 - 실패 상태로 저장
            log.error("PG사 결제 실패: 결제ID={}, 오류={}", savedPayment.getId(), e.getMessage());
            Payment failedPayment = updatePaymentStatus(savedPayment.getId(), PaymentStatus.FAILED, null);
            
            // 6. 결제 실패 이벤트 발행 (Redis Pub/Sub)
            PaymentCompletedEvent event = PaymentCompletedEvent.of(
                    failedPayment.getId(),
                    failedPayment.getOrder().getId(),
                    failedPayment.getAmount(),
                    null,
                    failedPayment.getStatus().name()
            );
            eventPublisher.publishPaymentCompleted(event);
            log.info("결제 실패 이벤트 발행 완료: 결제ID={}, 주문ID={}", failedPayment.getId(), failedPayment.getOrder().getId());
            
            // 실패한 결제도 저장되었으므로 예외를 throw
            throw new OrderException("결제 처리에 실패했습니다: " + e.getMessage(), e);
        }
    }
    
    /**
     * 결제 엔티티 저장 (별도 트랜잭션으로 저장하여 롤백 방지)
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Payment savePaymentEntity(Order order, PaymentCreateRequest request) {
        Payment payment = Payment.builder()
                .order(order)
                .amount(request.getAmount())
                .method(request.getMethod())
                .status(PaymentStatus.PENDING)
                .build();
        
        return paymentRepository.save(payment);
    }
    
    /**
     * 결제 상태 업데이트 (별도 트랜잭션으로 저장하여 롤백 방지)
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Payment updatePaymentStatus(Long paymentId, PaymentStatus status, String transactionId) {
        // detached 엔티티 문제 방지를 위해 ID로 다시 조회
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new OrderException("결제를 찾을 수 없습니다: " + paymentId));
        
        // 상태에 따라 처리
        if (status == PaymentStatus.COMPLETED && transactionId != null) {
            payment.complete(transactionId);
        } else if (status == PaymentStatus.FAILED) {
            payment.fail();
        }
        
        return paymentRepository.save(payment);
    }
    
    @Override
    public PaymentResponse getPayment(Long id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new OrderException("결제를 찾을 수 없습니다: " + id));
        return PaymentResponse.from(payment);
    }
    
    @Override
    @Transactional
    @Retryable(
        retryFor = PaymentGatewayException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public PaymentResponse refundPayment(Long paymentId) {
        log.info("환불 처리 시작: 결제ID={}", paymentId);
        
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new OrderException("결제를 찾을 수 없습니다: " + paymentId));
        
        if (!payment.isCompleted()) {
            throw new OrderException("완료된 결제만 환불할 수 있습니다");
        }
        
        try {
            // PG사 환불 요청 (재시도 가능)
            paymentGatewayService.requestRefund(payment.getTransactionId());
            
            // 환불 처리
            payment.refund();
            paymentRepository.save(payment);
            
            log.info("환불 완료: 결제ID={}", paymentId);
            return PaymentResponse.from(payment);
            
        } catch (PaymentGatewayException e) {
            log.error("PG사 환불 실패: 결제ID={}, 오류={}", paymentId, e.getMessage());
            throw new OrderException("환불 처리에 실패했습니다: " + e.getMessage(), e);
        }
    }
}
```

---

## 시퀀스 다이어그램 (흐름)

### 주문 → 결제 → 주문 상태

1. `POST /api/orders` → `OrderServiceImpl.createOrder` → DB 저장 → `OrderCreatedEvent` 발행
2. `OrderCreatedEventListener` 수신 → `PaymentClient.processPayment` (비동기)
3. Payment 서비스 내부에서 결제 저장 → `PaymentCompletedEvent` 발행
4. `PaymentCompletedEventListener` 수신 → 주문 상태 업데이트

### DLQ

1. 리스너에서 예외 → `DeadLetterMessage` insert
2. `DlqProcessor` 주기 실행 → Redis 재발행 → 정상 처리되면 `PROCESSED`

---

---


## 실행 방법

### 1. Redis

```bash
docker run -d -p 6379:6379 redis:latest
```

### 2. 프로필별 기동 (MSA 실습)

터미널 두 개 + Redis 하나를 권장합니다.

```bash
# Order (8080) — Consumer + 주문 API
./gradlew bootRun --args='--spring.profiles.active=order'

# Payment (8081) — 결제 API + 결제 완료 이벤트 발행
./gradlew bootRun --args='--spring.profiles.active=payment'
```

단일 프로세스 기본 프로필만으로는 Redis/이벤트 실습이 맞지 않을 수 있으므로, **이 장 실습은 `order` / `payment` 프로필과 Redis를 사용**한다고 보면 됩니다.

### 3. 주문 생성

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d "{
    \"customerId\": \"customer-1\",
    \"productId\": \"product-1\",
    \"quantity\": 2,
    \"totalPrice\": 20000,
    \"paymentMethod\": \"CREDIT_CARD\"
  }"
```

### 4. 로그로 확인할 내용

- Order: 주문 생성, `주문 생성 이벤트 발행`, `이벤트 수신: 채널=order:created`, 비동기 결제 완료 로그
- Payment: 결제 처리, `결제 완료 이벤트 발행`
- Order: `payment:completed` 수신, 주문 `CONFIRMED` 또는 실패 시 `CANCELLED`

---

## 장점·주의사항

**장점**

- 주문 API 응답이 결제 완료를 기다리지 않아 **체감 지연 감소**
- Order / Payment **배포 단위 분리**에 맞는 통신 방식

**주의**

- Redis Pub/Sub **비영속** — 프로세스 다운 시 미전달 메시지는 사라짐
- **동일 브로커·채널 이름** 맞추기
- `StringRedisTemplate` + 수동 JSON이 아니면 이중 직렬화에 주의

---

## 다음 단계

- Redis Stream, Kafka 등 **영속·소비 그룹** 있는 메시징
- Saga / 이벤트 소싱 등 분산 트랜잭션 패턴

---

## 참고 자료

- [Redis Pub/Sub](https://redis.io/docs/manual/pubsub/)
- [Spring Data Redis](https://docs.spring.io/spring-data/redis/docs/current/reference/html/)
- [Event-driven architecture](https://microservices.io/patterns/data/event-driven-architecture.html)
