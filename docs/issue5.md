# Ch06.05: 결제 로직 실습 가이드

## 실습 목표
- 결제 프로세스 이해
- 상태 관리 (State Machine: PENDING → COMPLETED/FAILED)
- PaymentService 구현 (@Transactional, PG 호출)
- 에러 처리 및 재시도 (타임아웃, @Retryable)
- 통합 테스트 (@SpringBootTest)

## 검증 결과
✅ **모든 실습 단계 정상 작동 확인**
- 모든 파일 존재 및 컴파일 성공
- 통합 테스트 통과
- 재시도 로직 정상 작동
- 상태 전이 정상 작동

---

## 실습 순서

### 1단계: Payment 엔티티 및 상태 확인

**파일 위치:**
```
src/main/java/com/ccommit/monolith_to_msa/domain/payment/
├── Payment.java
├── PaymentStatus.java
└── PaymentMethod.java
```

**확인 사항:**
- ✅ `PaymentStatus`: PENDING, COMPLETED, FAILED, REFUNDED
- ✅ `complete()`: PENDING → COMPLETED 상태 전이
- ✅ `fail()`: PENDING → FAILED 상태 전이
- ✅ `refund()`: COMPLETED → REFUNDED 상태 전이

**상태 전이 다이어그램:**
```
PENDING → COMPLETED (결제 성공)
       → FAILED (결제 실패)
COMPLETED → REFUNDED (환불)
```

**실습:**
```bash
cat src/main/java/com/ccommit/monolith_to_msa/domain/payment/Payment.java
cat src/main/java/com/ccommit/monolith_to_msa/domain/payment/PaymentStatus.java
```

---

### 2단계: PaymentGatewayService 확인

**파일 위치:**
```
src/main/java/com/ccommit/monolith_to_msa/service/payment/PaymentGatewayService.java
```

**확인 사항:**
- ✅ `requestPayment()`: PG사 결제 요청 (Mock)
- ✅ 네트워크 지연 시뮬레이션 (0.5~2초)
- ✅ 10% 확률로 실패 (타임아웃 시뮬레이션)
- ✅ `requestRefund()`: PG사 환불 요청

**실습:**
```bash
cat src/main/java/com/ccommit/monolith_to_msa/service/payment/PaymentGatewayService.java
```

---

### 3단계: PaymentService 구현 확인

**파일 위치:**
```
src/main/java/com/ccommit/monolith_to_msa/service/payment/
├── PaymentService.java
└── PaymentServiceImpl.java
```

**결제 처리 로직 순서:**
1. 주문 조회
2. 결제 엔티티 생성 (PENDING 상태)
3. PG사 결제 요청 (@Retryable)
4. 결제 완료 처리 (COMPLETED) 또는 실패 처리 (FAILED)

**트랜잭션:**
- ✅ `@Transactional`: 전체 작업을 하나의 트랜잭션으로 처리
- 실패 시 자동 롤백

**재시도 로직:**
- ✅ `@Retryable`: PaymentGatewayException 발생 시 최대 3회 재시도
- ✅ `@Backoff`: 지수 백오프 (1초, 2초, 4초)

**실습:**
```bash
cat src/main/java/com/ccommit/monolith_to_msa/service/payment/PaymentServiceImpl.java
```

---

### 4단계: 재시도 설정 확인

**파일 위치:**
```
src/main/java/com/ccommit/monolith_to_msa/config/RetryConfig.java
```

**확인 사항:**
- ✅ `@EnableRetry`: 재시도 기능 활성화
- ✅ `@Retryable` 어노테이션 사용 가능

**재시도 설정:**
```java
@Retryable(
    retryFor = PaymentGatewayException.class,
    maxAttempts = 3,
    backoff = @Backoff(delay = 1000, multiplier = 2)
)
```

**실습:**
```bash
cat src/main/java/com/ccommit/monolith_to_msa/config/RetryConfig.java
```

---

### 5단계: PaymentController 확인

**파일 위치:**
```
src/main/java/com/ccommit/monolith_to_msa/controller/payment/PaymentController.java
```

**API 엔드포인트:**
- ✅ `POST /api/payments`: 결제 처리
- ✅ `GET /api/payments/{id}`: 결제 조회
- ✅ `POST /api/payments/{id}/refund`: 환불 처리

**실습:**
```bash
cat src/main/java/com/ccommit/monolith_to_msa/controller/payment/PaymentController.java
```

---

### 6단계: 통합 테스트 확인

**파일 위치:**
```
src/test/java/com/ccommit/monolith_to_msa/integration/PaymentIntegrationTest.java
```

**테스트 케이스:**
- 결제 처리 성공
- 결제 조회
- 환불 처리
- 상태 전이 확인 (PENDING → COMPLETED/FAILED)

**@SpringBootTest:**
- 전체 스택 테스트
- 실제 DB 사용 (트랜잭션 롤백)

**실습:**
```bash
cat src/test/java/com/ccommit/monolith_to_msa/integration/PaymentIntegrationTest.java
```

---

### 7단계: 빌드 및 테스트 실행

**빌드:**
```bash
./gradlew clean build
```
✅ **예상 결과:** BUILD SUCCESSFUL

**통합 테스트 실행:**
```bash
./gradlew test --tests PaymentIntegrationTest
```
✅ **예상 결과:** BUILD SUCCESSFUL - 모든 테스트 통과

**전체 테스트 실행:**
```bash
./gradlew test
```
✅ **예상 결과:** BUILD SUCCESSFUL

---

### 8단계: 애플리케이션 실행 및 API 테스트

**애플리케이션 실행:**
```bash
./gradlew bootRun
```

**주문 생성 (선행 작업):**
```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "customer-001",
    "productId": "product-001",
    "quantity": 2,
    "totalPrice": 20000
  }'
```

**결제 처리:**
```bash
curl -X POST http://localhost:8080/api/payments \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": 1,
    "amount": 20000,
    "method": "CREDIT_CARD"
  }'
```

**예상 응답 (201 Created):**
```json
{
  "id": 1,
  "orderId": 1,
  "amount": 20000,
  "method": "CREDIT_CARD",
  "status": "COMPLETED",
  "transactionId": "TXN-1234567890-123",
  "paidAt": "2026-01-30T20:00:00",
  "createdAt": "2026-01-30T20:00:00",
  "updatedAt": "2026-01-30T20:00:00"
}
```

**결제 조회:**
```bash
curl http://localhost:8080/api/payments/1
```

**환불 처리:**
```bash
curl -X POST http://localhost:8080/api/payments/1/refund
```

**예상 응답:**
```json
{
  "id": 1,
  "orderId": 1,
  "amount": 20000,
  "method": "CREDIT_CARD",
  "status": "REFUNDED",
  "transactionId": "TXN-1234567890-123",
  "paidAt": "2026-01-30T20:00:00",
  "createdAt": "2026-01-30T20:00:00",
  "updatedAt": "2026-01-30T20:01:00"
}
```

---

## 핵심 개념

### 1. 결제 프로세스

**처리 순서:**
1. 주문 조회
2. 결제 엔티티 생성 (PENDING)
3. PG사 결제 요청
4. 결제 완료 (COMPLETED) 또는 실패 (FAILED)

**트랜잭션:**
- `@Transactional`: 전체 작업을 하나의 트랜잭션으로 처리
- PG 호출 실패 시 결제 엔티티도 롤백되지 않음 (FAILED 상태로 저장)

### 2. 상태 관리 (State Machine)

**상태 전이:**
```
PENDING (초기 상태)
  ↓ (결제 성공)
COMPLETED
  ↓ (환불)
REFUNDED

PENDING
  ↓ (결제 실패)
FAILED
```

**상태 전이 규칙:**
- PENDING → COMPLETED: `complete()` 메서드
- PENDING → FAILED: `fail()` 메서드
- COMPLETED → REFUNDED: `refund()` 메서드
- 잘못된 전이 시 `IllegalStateException` 발생

### 3. 에러 처리 및 재시도

**@Retryable:**
- `retryFor`: 재시도할 예외 타입
- `maxAttempts`: 최대 재시도 횟수 (3회)
- `backoff`: 백오프 전략 (지수 백오프)

**재시도 시나리오:**
1. 1차 시도: 즉시
2. 2차 시도: 1초 후
3. 3차 시도: 2초 후
4. 실패: 예외 발생

**타임아웃 처리:**
- PG Gateway Service에서 네트워크 지연 시뮬레이션
- 10% 확률로 실패 (타임아웃 시뮬레이션)

### 4. 통합 테스트

**@SpringBootTest:**
- 전체 스택 테스트
- 실제 Spring 컨텍스트 로드
- 실제 DB 사용 (트랜잭션 롤백)

**테스트 전략:**
- Service 레이어 테스트
- 상태 전이 검증
- 재시도 로직 검증

---

## 프로젝트 구조

```
src/main/java/com/ccommit/monolith_to_msa/
├── domain/
│   └── payment/
│       ├── Payment.java
│       ├── PaymentStatus.java
│       └── PaymentMethod.java
├── repository/
│   └── payment/
│       └── PaymentRepository.java
├── service/
│   └── payment/
│       ├── PaymentService.java
│       ├── PaymentServiceImpl.java
│       ├── PaymentGatewayService.java
│       └── PaymentGatewayException.java
├── controller/
│   └── payment/
│       └── PaymentController.java
├── dto/
│   └── payment/
│       ├── PaymentCreateRequest.java
│       └── PaymentResponse.java
└── config/
    └── RetryConfig.java

src/test/java/com/ccommit/monolith_to_msa/
└── integration/
    └── PaymentIntegrationTest.java
```

---

## 실습 체크리스트

- [ ] 1단계: Payment 엔티티 및 상태 확인
- [ ] 2단계: PaymentGatewayService 확인
- [ ] 3단계: PaymentService 구현 확인
- [ ] 4단계: 재시도 설정 확인
- [ ] 5단계: PaymentController 확인
- [ ] 6단계: 통합 테스트 확인
- [ ] 7단계: 빌드 및 테스트 실행
- [ ] 8단계: 애플리케이션 실행 및 API 테스트

---

## 문제 해결

### 재시도가 작동하지 않음

**확인 사항:**
- `@EnableRetry` 어노테이션이 `RetryConfig`에 있는지 확인
- `@Retryable` 어노테이션이 올바르게 설정되었는지 확인
- Spring Retry 의존성이 추가되었는지 확인

### 상태 전이가 안 됨

**확인 사항:**
- Payment 엔티티의 상태 전이 메서드 확인
- `complete()`, `fail()`, `refund()` 메서드 호출 확인
- 트랜잭션이 제대로 커밋되었는지 확인

### PG 호출 실패

**확인 사항:**
- PaymentGatewayService의 Mock 로직 확인
- 네트워크 지연 시뮬레이션 확인
- 재시도 로직이 작동하는지 확인

---

## 참고 자료

- **시퀀스 다이어그램:** `issue5.puml` 파일 참조
- **상세 개념 설명:** 각 단계별 파일 확인

