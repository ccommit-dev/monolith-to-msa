# Ch06.11: 비동기 처리 실습 가이드

## 실습 목표
- Redis Pub/Sub을 통한 이벤트 기반 아키텍처 구현
- 비동기 Consumer를 통한 결제 처리
- Dead Letter Queue (DLQ)를 통한 실패 메시지 재처리
- 이벤트 기반 아키텍처의 장점 이해

---

## 전체 구조

### 1. Redis Pub/Sub
- **Publisher**: 주문 생성 시 이벤트 발행
- **Subscriber**: 이벤트 구독 및 비동기 처리
- **채널**: `order:created`, `payment:completed`

### 2. 이벤트 기반 아키텍처
- **OrderCreated → PaymentCompleted**: 주문 생성 후 결제 처리
- **비동기 처리**: 주문 생성과 결제 처리가 분리
- **느슨한 결합**: 서비스 간 직접 의존성 제거

### 3. 비동기 Consumer
- **OrderCreatedEventListener**: 주문 생성 이벤트 구독 및 결제 처리
- **PaymentCompletedEventListener**: 결제 완료 이벤트 구독 및 주문 상태 업데이트

### 4. DLQ (Dead Letter Queue)
- **실패 메시지 저장**: 처리 실패한 메시지를 DB에 저장
- **재처리 스케줄러**: 주기적으로 DLQ 메시지 재처리
- **최대 재시도**: 3회 재시도 후 실패 처리

---

## 핵심 메시지

### 1. Redis Pub/Sub
- **Publish → Subscribe**: 발행-구독 패턴
- **비동기 통신**: 발행자는 구독자의 응답을 기다리지 않음
- **확장성**: 여러 구독자가 동일한 이벤트를 처리 가능

### 2. 이벤트 기반 아키텍처
- **OrderCreated → PaymentCompleted**: 이벤트 체인
- **느슨한 결합**: 서비스 간 직접 호출 제거
- **장애 격리**: 한 서비스의 장애가 다른 서비스에 전파되지 않음

### 3. 비동기 Consumer
- **Non-blocking**: 주문 생성과 결제 처리가 비동기로 분리
- **성능 향상**: 응답 시간 단축
- **확장성**: Consumer 인스턴스 추가로 처리량 증가

### 4. DLQ (Dead Letter Queue)
- **실패 처리**: 처리 실패한 메시지를 DLQ에 저장
- **재처리**: 스케줄러를 통한 주기적 재처리
- **모니터링**: 실패 메시지 추적 및 분석

---

## 실습 순서

### 1단계: 이벤트 도메인 모델 생성

**파일 위치:**
```
src/main/java/com/ccommit/monolith_to_msa/domain/event/
├── OrderCreatedEvent.java
├── PaymentCompletedEvent.java
└── DeadLetterMessage.java
```

**OrderCreatedEvent:**
- 주문 생성 이벤트
- 주문ID, 고객ID, 상품ID, 수량, 총액, 결제수단 포함

**PaymentCompletedEvent:**
- 결제 완료 이벤트
- 결제ID, 주문ID, 금액, 거래ID, 상태 포함

**DeadLetterMessage:**
- DLQ 엔티티
- 채널, 메시지, 에러 메시지, 재시도 횟수, 상태 포함

---

### 2단계: Redis Pub/Sub 설정

**파일 위치:**
```
src/main/java/com/ccommit/monolith_to_msa/config/
├── RedisPubSubConfig.java
└── RedisListenerConfig.java
```

**RedisPubSubConfig:**
- RedisTemplate 설정 (JSON 직렬화)
- RedisMessageListenerContainer 설정
- 채널 토픽 정의

**RedisListenerConfig:**
- 이벤트 리스너 등록
- OrderCreatedEventListener, PaymentCompletedEventListener 등록

**application-order.yaml:**
```yaml
spring:
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
```

---

### 3단계: 이벤트 Publisher 구현

**파일 위치:**
```
src/main/java/com/ccommit/monolith_to_msa/service/event/
└── EventPublisher.java
```

**기능:**
- `publishOrderCreated()`: 주문 생성 이벤트 발행
- `publishPaymentCompleted()`: 결제 완료 이벤트 발행
- Redis Pub/Sub을 통한 이벤트 발행

---

### 4단계: 비동기 Consumer 구현

**파일 위치:**
```
src/main/java/com/ccommit/monolith_to_msa/service/event/
├── OrderCreatedEventListener.java
└── PaymentCompletedEventListener.java
```

**OrderCreatedEventListener:**
- `order:created` 채널 구독
- 주문 생성 이벤트 수신 시 결제 처리 (비동기)
- 처리 실패 시 DLQ에 저장

**PaymentCompletedEventListener:**
- `payment:completed` 채널 구독
- 결제 완료 이벤트 수신 시 주문 상태 업데이트
- 처리 실패 시 DLQ에 저장

---

### 5단계: DLQ 구현

**파일 위치:**
```
src/main/java/com/ccommit/monolith_to_msa/
├── domain/event/DeadLetterMessage.java
├── repository/event/DeadLetterMessageRepository.java
└── service/event/DlqProcessor.java
```

**DeadLetterMessage:**
- DLQ 엔티티 (JPA)
- 채널, 메시지, 에러 메시지, 재시도 횟수, 상태 관리

**DeadLetterMessageRepository:**
- JPA Repository
- 상태별, 채널별 조회 메서드

**DlqProcessor:**
- 스케줄러를 통한 DLQ 메시지 재처리
- 1분마다 실행 (`@Scheduled`)
- 최대 3회 재시도

---

### 6단계: 서비스 수정

**OrderServiceImpl:**
- 주문 생성 후 `OrderCreatedEvent` 발행
- 기존 동기 결제 호출 제거

**PaymentServiceImpl:**
- 결제 완료/실패 후 `PaymentCompletedEvent` 발행
- 이벤트 기반 주문 상태 업데이트

---

## 시퀀스 다이어그램

### 주문 생성 → 결제 처리 흐름

1. **주문 생성**
   - OrderService.createOrder()
   - 주문 저장 (PENDING 상태)
   - OrderCreatedEvent 발행

2. **이벤트 구독**
   - OrderCreatedEventListener.onMessage()
   - 결제 처리 (비동기)

3. **결제 처리**
   - PaymentService.processPayment()
   - PaymentCompletedEvent 발행

4. **주문 상태 업데이트**
   - PaymentCompletedEventListener.onMessage()
   - 주문 상태 업데이트 (CONFIRMED/CANCELLED)

### DLQ 재처리 흐름

1. **처리 실패**
   - 이벤트 처리 중 예외 발생
   - DLQ에 메시지 저장

2. **재처리 스케줄러**
   - DlqProcessor.processDlqMessages() (1분마다)
   - PENDING 상태 메시지 조회
   - 이벤트 재발행

3. **재시도**
   - 최대 3회 재시도
   - 성공 시 PROCESSED 상태
   - 실패 시 FAILED 상태

---

## 코드 예시

### OrderServiceImpl (이벤트 발행)

```java
@Override
@Transactional
public OrderResponse createOrder(OrderCreateRequest request) {
    // ... 주문 생성 로직 ...
    
    Order savedOrder = orderRepository.save(order);
    
    // 주문 생성 이벤트 발행 (Redis Pub/Sub)
    OrderCreatedEvent event = OrderCreatedEvent.of(
            savedOrder.getId(),
            savedOrder.getCustomerId(),
            savedOrder.getProductId(),
            savedOrder.getQuantity(),
            savedOrder.getTotalPrice(),
            request.getPaymentMethod().name()
    );
    eventPublisher.publishOrderCreated(event);
    
    return OrderResponse.from(savedOrder);
}
```

### OrderCreatedEventListener (비동기 Consumer)

```java
@Override
public void onMessage(Message message, byte[] pattern) {
    String body = new String(message.getBody());
    OrderCreatedEvent event = objectMapper.readValue(body, OrderCreatedEvent.class);
    
    // 결제 처리 (비동기)
    PaymentCreateRequest paymentRequest = PaymentCreateRequest.builder()
            .orderId(event.getOrderId())
            .amount(event.getTotalPrice())
            .method(PaymentMethod.valueOf(event.getPaymentMethod()))
            .build();
    
    paymentClient.processPayment(paymentRequest)
            .subscribe(
                    response -> log.info("결제 완료: {}", response.getId()),
                    error -> log.error("결제 실패: {}", error.getMessage())
            );
}
```

### DlqProcessor (DLQ 재처리)

```java
@Scheduled(fixedDelay = 60000) // 1분마다
@Transactional
public void processDlqMessages() {
    List<DeadLetterMessage> pendingMessages = dlqRepository
            .findByStatusAndRetryCountLessThan(
                    MessageStatus.PENDING, 
                    MAX_RETRY_COUNT);
    
    for (DeadLetterMessage dlqMessage : pendingMessages) {
        try {
            retryMessage(dlqMessage);
            dlqMessage.markAsProcessed();
        } catch (Exception e) {
            dlqMessage.incrementRetryCount();
            if (dlqMessage.getRetryCount() >= MAX_RETRY_COUNT) {
                dlqMessage.markAsFailed();
            }
        }
        dlqRepository.save(dlqMessage);
    }
}
```

---

## 실행 방법

### 1. Redis 실행

```bash
# Docker로 Redis 실행
docker run -d -p 6379:6379 redis:latest

# 또는 로컬 Redis 실행
redis-server
```

### 2. 애플리케이션 실행

```bash
# 애플리케이션 실행
./gradlew bootRun

# 또는
java -jar build/libs/monolith-to-msa-0.0.1-SNAPSHOT.jar
```

### 3. 주문 생성 테스트

```bash
# 주문 생성 API 호출
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "customer-1",
    "productId": "product-1",
    "quantity": 2,
    "totalPrice": 20000,
    "paymentMethod": "CREDIT_CARD"
  }'
```

### 4. 로그 확인

```bash
# 주문 생성 이벤트 발행 로그
INFO  - 주문 생성 완료: 주문ID=1, 상태=PENDING
INFO  - 주문 생성 이벤트 발행: 주문ID=1, 채널=order:created

# 이벤트 수신 로그
INFO  - 이벤트 수신: 채널=order:created, 메시지={...}
INFO  - 주문 생성 이벤트 처리 시작: 주문ID=1

# 결제 처리 로그
INFO  - 결제 처리 시작: 주문ID=1, 금액=20000
INFO  - 결제 완료: 결제ID=1, 거래ID=txn-123
INFO  - 결제 완료 이벤트 발행: 결제ID=1, 주문ID=1

# 주문 상태 업데이트 로그
INFO  - 주문 상태 업데이트 완료: 주문ID=1, 상태=CONFIRMED
```

---

## 장점

### 1. 비동기 처리
- **응답 시간 단축**: 주문 생성과 결제 처리가 분리
- **성능 향상**: Non-blocking 처리

### 2. 확장성
- **Consumer 확장**: 여러 Consumer 인스턴스로 처리량 증가
- **서비스 독립성**: 서비스 간 직접 의존성 제거

### 3. 장애 격리
- **DLQ**: 실패 메시지 저장 및 재처리
- **재시도**: 자동 재처리 메커니즘

### 4. 모니터링
- **이벤트 추적**: 이벤트 발행/수신 로그
- **DLQ 모니터링**: 실패 메시지 추적

---

## 주의사항

### 1. Redis 연결
- Redis가 실행 중이어야 함
- 연결 실패 시 이벤트 발행 실패

### 2. 메시지 순서
- Redis Pub/Sub은 메시지 순서 보장하지 않음
- 순서가 중요한 경우 다른 메시징 시스템 고려

### 3. 메시지 손실
- 구독자가 없으면 메시지 손실 가능
- 영속성이 필요한 경우 Redis Stream 고려

### 4. DLQ 재처리
- 스케줄러 실행 주기 조정 가능
- 최대 재시도 횟수 조정 가능

---

## 다음 단계

1. **Redis Stream**: 영속성 있는 메시징
2. **이벤트 소싱**: 이벤트 저장 및 재생
3. **Saga 패턴**: 분산 트랜잭션 처리
4. **CQRS**: 명령과 조회 분리

---

## 참고 자료

- [Redis Pub/Sub 문서](https://redis.io/docs/manual/pubsub/)
- [Spring Data Redis 문서](https://docs.spring.io/spring-data/redis/docs/current/reference/html/)
- [이벤트 기반 아키텍처 패턴](https://microservices.io/patterns/data/event-driven-architecture.html)
