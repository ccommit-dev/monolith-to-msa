# Ch06.10: 통신 구조 실습 가이드

## 실습 목표
- RestTemplate → WebClient 전환 (Non-blocking 통신)
- Resilience4j 적용 (Circuit Breaker, Retry, Timeout)
- Fallback 구현 (Payment 실패 시 보류 처리)
- 서비스 간 안정적인 통신 구조 구축

---

## 전체 구조 (40분 로드맵)

### 1단계: WebClient 전환 (10분)
- RestTemplate → WebClient 전환
- Non-blocking 통신 구현
- 타임아웃 및 연결 풀 설정

### 2단계: Resilience4j 설정 (10분)
- Circuit Breaker 설정
- Retry 설정
- Timeout 설정

### 3단계: Fallback 구현 (10분)
- Payment 실패 시 보류 처리
- 주문은 생성되지만 결제는 보류 상태로 처리

### 4단계: 통합 테스트 (10분)
- Order Service와 Payment Service 통신 테스트
- Circuit Breaker 동작 확인
- Fallback 동작 확인

---

## 핵심 메시지

### 1. 서비스 분리 전략
- **비즈니스 도메인 기준**: 주문과 결제는 독립적인 비즈니스 도메인
- **데이터 독립성**: 주문 데이터와 결제 데이터는 독립적으로 관리
- **확장성**: 서비스별 독립적 확장 가능
- **장애 격리**: 결제 실패가 주문 조회에 영향 없음

### 2. Order Service 구조
- **Port**: 8080
- **Database**: orderdb (독립적인 H2 인메모리 DB)
- **커넥션 풀**: 최대 20개 (독립적)
- **주요 기능**: 주문 생성, 조회, 상태 업데이트
- **외부 통신**: Payment Service 호출 (WebClient)

### 3. Payment Service 구조
- **Port**: 8081
- **Database**: paymentdb (독립적인 H2 인메모리 DB)
- **커넥션 풀**: 최대 20개 (독립적)
- **주요 기능**: 결제 처리, 조회, 상태 업데이트
- **외부 통신**: PG사 API 호출 (시뮬레이션)

### 4. API 통신
- **통신 방식**: REST API (HTTP)
- **클라이언트**: WebClient (Non-blocking)
- **장애 처리**: Circuit Breaker, Retry, Timeout
- **Fallback**: Payment 실패 시 보류 처리

### 5. 독립 배포
- **컨테이너화**: Docker 기반 배포
- **독립 확장**: 서비스별 인스턴스 수 조정
- **장점**: 빠른 배포, 롤백 용이, 리소스 효율성

---

## 실습 순서

### 1단계: WebClient 전환

#### 1.1 의존성 추가

**build.gradle:**
```gradle
// WebClient (Reactive Web)
implementation 'org.springframework.boot:spring-boot-starter-webflux'

// Resilience4j (Circuit Breaker, Retry, Timeout)
implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.1.0'
implementation 'io.github.resilience4j:resilience4j-reactor:2.1.0'
```

#### 1.2 WebClient Bean 설정

**WebConfig.java:**
```java
@Bean
public WebClient webClient() {
    HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)  // 연결 타임아웃: 5초
            .responseTimeout(Duration.ofSeconds(10))              // 응답 타임아웃: 10초
            .doOnConnected(conn ->
                    conn.addHandlerLast(new ReadTimeoutHandler(10, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(10, TimeUnit.SECONDS))
            );
    
    return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
}
```

**설명:**
- `CONNECT_TIMEOUT_MILLIS`: 연결 타임아웃 (5초)
- `responseTimeout`: 응답 타임아웃 (10초)
- `ReadTimeoutHandler`: 읽기 타임아웃 핸들러
- `WriteTimeoutHandler`: 쓰기 타임아웃 핸들러

#### 1.3 PaymentClient 전환

**Before (RestTemplate):**
```java
@Service
public class PaymentClient {
    private final RestTemplate restTemplate;
    
    public PaymentResponse processPayment(PaymentCreateRequest request) {
        ResponseEntity<PaymentResponse> response = restTemplate.postForEntity(
            paymentServiceUrl + "/api/payments",
            request,
            PaymentResponse.class
        );
        return response.getBody();
    }
}
```

**After (WebClient):**
```java
@Service
public class PaymentClient {
    private final WebClient webClient;
    
    public Mono<PaymentResponse> processPayment(PaymentCreateRequest request) {
        return webClient.post()
                .uri(paymentServiceUrl + "/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(PaymentResponse.class);
    }
}
```

**차이점:**
- **Blocking vs Non-blocking**: RestTemplate은 블로킹, WebClient는 논블로킹
- **반환 타입**: RestTemplate은 `ResponseEntity`, WebClient는 `Mono<T>`
- **성능**: WebClient는 더 적은 스레드로 더 많은 요청 처리 가능

---

### 2단계: Resilience4j 설정

#### 2.1 application-order.yaml 설정

**Resilience4j 설정:**
```yaml
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
  
  timelimiter:
    instances:
      paymentService:
        timeoutDuration: 5s                      # 타임아웃 시간
        cancelRunningFuture: true                 # 실행 중인 Future 취소
```

**설명:**
- **Circuit Breaker**: 실패율이 50% 이상이면 Open 상태로 전환
- **Retry**: 최대 3회 재시도, 지수 백오프 적용
- **Timeout**: 5초 타임아웃 설정

#### 2.2 PaymentClient에 Resilience4j 적용

**PaymentClient.java:**
```java
@CircuitBreaker(name = "paymentService", fallbackMethod = "processPaymentFallback")
@Retry(name = "paymentService")
@TimeLimiter(name = "paymentService")
public Mono<PaymentResponse> processPayment(PaymentCreateRequest request) {
    return webClient.post()
            .uri(paymentServiceUrl + "/api/payments")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .bodyToMono(PaymentResponse.class)
            .transformDeferred(CircuitBreakerOperator.of("paymentService"))
            .transformDeferred(RetryOperator.of("paymentService"))
            .timeout(Duration.ofSeconds(5));
}
```

**설명:**
- `@CircuitBreaker`: Circuit Breaker 적용
- `@Retry`: 재시도 로직 적용
- `@TimeLimiter`: 타임아웃 적용
- `transformDeferred`: Reactive 스트림에 Resilience4j 연산자 적용

---

### 3단계: Fallback 구현

#### 3.1 Fallback 메서드 구현

**PaymentClient.java:**
```java
private Mono<PaymentResponse> processPaymentFallback(
        PaymentCreateRequest request, 
        Exception ex) {
    log.warn("Payment Service Fallback 실행: 주문ID={}, 오류={}", 
            request.getOrderId(), ex.getMessage());
    
    // Fallback: 보류 상태의 결제 응답 반환
    PaymentResponse fallbackResponse = PaymentResponse.builder()
            .id(null)  // 결제 ID는 없음 (보류 상태)
            .orderId(request.getOrderId())
            .amount(request.getAmount())
            .method(request.getMethod())
            .status(PaymentStatus.PENDING)
            .transactionId(null)
            .build();
    
    return Mono.just(fallbackResponse);
}
```

**설명:**
- Payment Service 호출 실패 시 Fallback 실행
- 보류 상태(PENDING)의 결제 응답 반환
- 주문은 생성되지만 결제는 나중에 처리 가능

#### 3.2 OrderServiceImpl에서 Fallback 처리

**OrderServiceImpl.java:**
```java
@Override
@Transactional
public OrderResponse createOrder(OrderCreateRequest request) {
    // 1-5. 주문 생성 및 저장
    Order savedOrder = orderRepository.save(order);
    
    // 6. Payment Service 호출 (Non-blocking)
    try {
        PaymentResponse paymentResponse = paymentClient.processPayment(paymentRequest)
                .blockOptional()
                .orElse(null);
        
        if (paymentResponse != null) {
            if (paymentResponse.getStatus() == PaymentStatus.COMPLETED) {
                // 결제 완료: 주문 상태를 CONFIRMED로 변경
                savedOrder.updateStatus(OrderStatus.CONFIRMED);
            } else if (paymentResponse.getStatus() == PaymentStatus.PENDING) {
                // Fallback으로 인한 보류 상태: 주문은 유지, 결제는 나중에 처리
                log.warn("결제 보류: 주문ID={}, 상태=PENDING (Fallback)", savedOrder.getId());
            }
        }
    } catch (PaymentServiceException e) {
        // Payment Service 호출 실패: Fallback 처리
        // 주문은 생성되지만 결제는 보류 상태로 처리
        log.error("Payment Service 호출 실패 (Fallback): 주문ID={}", savedOrder.getId());
    }
    
    return OrderResponse.from(savedOrder);
}
```

**설명:**
- 결제 성공: 주문 상태를 CONFIRMED로 변경
- 결제 보류: 주문은 PENDING 상태로 유지 (나중에 결제 처리 가능)
- 결제 실패: 주문 취소

---

### 4단계: 통합 테스트

#### 4.1 정상 케이스 테스트

**주문 생성 → 결제 성공:**
```bash
# 1. Order Service에 주문 생성 요청
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "customer1",
    "productId": "product1",
    "quantity": 2,
    "totalPrice": 20000,
    "paymentMethod": "CREDIT_CARD"
  }'

# 예상 결과:
# - 주문 생성 성공
# - 결제 처리 성공
# - 주문 상태: CONFIRMED
```

#### 4.2 Circuit Breaker 동작 확인

**Payment Service 장애 시:**
```bash
# 1. Payment Service 중지
docker stop payment-service

# 2. Order Service에 주문 생성 요청 (여러 번)
for i in {1..10}; do
  curl -X POST http://localhost:8080/api/orders \
    -H "Content-Type: application/json" \
    -d '{
      "customerId": "customer1",
      "productId": "product1",
      "quantity": 1,
      "totalPrice": 10000,
      "paymentMethod": "CREDIT_CARD"
    }'
  sleep 1
done

# 예상 결과:
# - 처음 몇 번은 재시도 시도
# - 실패율이 50% 이상이면 Circuit Breaker Open
# - 이후 요청은 Fallback 실행
# - 주문은 생성되지만 결제는 보류 상태
```

#### 4.3 Fallback 동작 확인

**Fallback 응답 확인:**
```bash
# 1. Payment Service 중지 상태에서 주문 생성
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "customer1",
    "productId": "product1",
    "quantity": 1,
    "totalPrice": 10000,
    "paymentMethod": "CREDIT_CARD"
  }'

# 예상 결과:
# - 주문 생성 성공
# - 결제는 보류 상태 (PENDING)
# - 주문 상태: PENDING
# - 로그에 "Payment Service Fallback 실행" 메시지 확인
```

#### 4.4 Circuit Breaker 상태 확인

**Actuator 엔드포인트:**
```bash
# Circuit Breaker 상태 확인
curl http://localhost:8080/actuator/health

# Circuit Breaker 이벤트 확인
curl http://localhost:8080/actuator/circuitbreakerevents

# 예상 결과:
# - Circuit Breaker 상태: OPEN (장애 시)
# - 실패 이벤트 기록 확인
```

---

## 주요 코드 파일

### 1. PaymentClient.java
- WebClient 기반 Non-blocking 통신
- Resilience4j 적용 (Circuit Breaker, Retry, Timeout)
- Fallback 구현

### 2. WebConfig.java
- WebClient Bean 설정
- 타임아웃 및 연결 풀 설정

### 3. OrderServiceImpl.java
- PaymentClient 통합
- Fallback 처리 로직

### 4. application-order.yaml
- Resilience4j 설정
- Circuit Breaker, Retry, Timeout 설정

---

## 성능 비교

### RestTemplate vs WebClient

**RestTemplate (Blocking):**
- 스레드 풀: 요청당 1개 스레드 사용
- 동시 처리: 제한적 (스레드 풀 크기에 의존)
- 리소스 사용: 높음 (많은 스레드 필요)

**WebClient (Non-blocking):**
- 스레드 풀: 적은 수의 스레드로 많은 요청 처리
- 동시 처리: 높음 (논블로킹 I/O)
- 리소스 사용: 낮음 (적은 스레드로 처리)

**성능 향상:**
- 동시 처리량: 2-3배 증가
- 응답 시간: 유사하거나 약간 개선
- 리소스 사용: 50% 이상 감소

---

## 장애 처리 전략

### 1. Circuit Breaker
- **목적**: 장애 전파 방지
- **동작**: 실패율이 임계값 이상이면 Circuit Breaker Open
- **효과**: Payment Service 장애 시 Order Service 영향 최소화

### 2. Retry
- **목적**: 일시적 장애 처리
- **동작**: 최대 3회 재시도, 지수 백오프 적용
- **효과**: 네트워크 일시적 장애 자동 복구

### 3. Timeout
- **목적**: 응답 지연 방지
- **동작**: 5초 타임아웃 설정
- **효과**: 무한 대기 방지, 빠른 실패 처리

### 4. Fallback
- **목적**: 장애 시 대체 처리
- **동작**: Payment 실패 시 보류 상태로 처리
- **효과**: 주문은 생성되지만 결제는 나중에 처리 가능

---

## 모니터링

### 1. Actuator 엔드포인트
- `/actuator/health`: 서비스 상태 확인
- `/actuator/circuitbreakers`: Circuit Breaker 상태 확인
- `/actuator/circuitbreakerevents`: Circuit Breaker 이벤트 확인
- `/actuator/metrics`: 메트릭 확인

### 2. 로그 모니터링
- Payment Service 호출 시작/성공/실패 로그
- Circuit Breaker 상태 변경 로그
- Fallback 실행 로그

### 3. 메트릭 수집
- Circuit Breaker 상태 (OPEN/CLOSED/HALF_OPEN)
- 재시도 횟수
- 타임아웃 발생 횟수
- Fallback 실행 횟수

---

## 실습 체크리스트

- [ ] WebClient 의존성 추가
- [ ] WebClient Bean 설정
- [ ] PaymentClient를 WebClient로 전환
- [ ] Resilience4j 설정 추가
- [ ] Circuit Breaker 적용
- [ ] Retry 적용
- [ ] Timeout 적용
- [ ] Fallback 구현
- [ ] OrderServiceImpl에 PaymentClient 통합
- [ ] 정상 케이스 테스트
- [ ] Circuit Breaker 동작 확인
- [ ] Fallback 동작 확인
- [ ] 모니터링 설정

---

## 다음 단계

1. **비동기 처리**: 주문 생성과 결제 처리를 완전히 비동기로 분리
2. **이벤트 기반 통신**: 메시지 큐를 통한 이벤트 기반 통신
3. **서비스 메시**: Istio, Linkerd 등을 통한 서비스 메시 구현
4. **분산 추적**: Zipkin, Jaeger 등을 통한 분산 추적
5. **API 게이트웨이**: Spring Cloud Gateway를 통한 API 게이트웨이 구현
