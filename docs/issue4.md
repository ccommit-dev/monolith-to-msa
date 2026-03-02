# Ch06.04: 주문 API 실습 가이드

## 실습 목표
- 주문 생성 API 구현 이해
- 트랜잭션 관리 (ACID) 학습
- 재고 차감 + 주문 생성 로직 이해
- 예외 처리 전략 학습
- 단위 테스트 작성 및 실행

## 검증 결과
✅ **모든 실습 단계 정상 작동 확인** (2026-01-30)
- 모든 파일 존재 및 컴파일 성공
- 모든 단위 테스트 통과 (OrderServiceTest, OrderControllerTest)
- Mockito 사용 가능
- 트랜잭션 관리 및 예외 처리 정상 작동

---

## 실습 순서

### 1단계: Product 엔티티 확인

**파일 위치:**
```
src/main/java/com/ccommit/monolith_to_msa/domain/product/Product.java
```

**확인 사항:**
- ✅ `decreaseStock()`: 재고 차감 메서드
- ✅ `increaseStock()`: 재고 증가 메서드
- ✅ `isStockAvailable()`: 재고 확인 메서드

**실습:**
```bash
cat src/main/java/com/ccommit/monolith_to_msa/domain/product/Product.java
```

---

### 2단계: ProductRepository 확인

**파일 위치:**
```
src/main/java/com/ccommit/monolith_to_msa/repository/product/ProductRepository.java
```

**확인 사항:**
- ✅ `findByProductId()`: 일반 상품 조회
- ✅ `findByProductIdWithLock()`: **비관적 락**으로 상품 조회

**비관적 락:**
- `@Lock(LockModeType.PESSIMISTIC_WRITE)`: SELECT FOR UPDATE
- 동시성 제어로 재고 중복 차감 방지

**실습:**
```bash
cat src/main/java/com/ccommit/monolith_to_msa/repository/product/ProductRepository.java
```

---

### 3단계: OrderService 구현 확인

**파일 위치:**
```
src/main/java/com/ccommit/monolith_to_msa/service/order/OrderServiceImpl.java
```

**주문 생성 로직 순서:**
1. 상품 조회 (비관적 락)
2. 재고 확인
3. 재고 차감
4. 주문 생성
5. 주문 저장

**트랜잭션:**
- ✅ `@Transactional`: 전체 작업을 하나의 트랜잭션으로 처리
- 실패 시 자동 롤백

**실습:**
```bash
cat src/main/java/com/ccommit/monolith_to_msa/service/order/OrderServiceImpl.java
```

---

### 4단계: Custom Exception 확인

**파일 위치:**
```
src/main/java/com/ccommit/monolith_to_msa/exception/
├── OrderException.java
├── ProductNotFoundException.java
└── InsufficientStockException.java
```

**예외 계층 구조:**
```
OrderException (기본)
├── ProductNotFoundException (404)
└── InsufficientStockException (400)
```

**실습:**
```bash
ls -la src/main/java/com/ccommit/monolith_to_msa/exception/
```

---

### 5단계: GlobalExceptionHandler 확인

**파일 위치:**
```
src/main/java/com/ccommit/monolith_to_msa/exception/GlobalExceptionHandler.java
```

**처리하는 예외:**
- ✅ `ProductNotFoundException` → 404 Not Found
- ✅ `InsufficientStockException` → 400 Bad Request
- ✅ `OrderException` → 400 Bad Request
- ✅ `MethodArgumentNotValidException` → 400 Bad Request (Validation)
- ✅ `Exception` → 500 Internal Server Error

**실습:**
```bash
cat src/main/java/com/ccommit/monolith_to_msa/exception/GlobalExceptionHandler.java
```

---

### 6단계: 단위 테스트 확인

**테스트 파일:**
- ✅ `OrderServiceTest.java`: Given-When-Then 패턴, Mock 사용
- ✅ `OrderControllerTest.java`: MockMvc 사용, Mockito 적용

**테스트 케이스:**
- 주문 생성 성공
- 상품 없음 (404)
- 재고 부족 (400)
- 트랜잭션 롤백
- 입력 검증 오류 (400)

**실습:**
```bash
cat src/test/java/com/ccommit/monolith_to_msa/service/order/OrderServiceTest.java
cat src/test/java/com/ccommit/monolith_to_msa/controller/order/OrderControllerTest.java
```

---

### 7단계: 빌드 및 테스트 실행

**빌드:**
```bash
./gradlew clean build
```
✅ **예상 결과:** BUILD SUCCESSFUL

**전체 테스트 실행:**
```bash
./gradlew test
```
✅ **예상 결과:** BUILD SUCCESSFUL - 모든 테스트 통과

**특정 테스트 실행:**
```bash
./gradlew test --tests OrderServiceTest
./gradlew test --tests OrderControllerTest
```
✅ **예상 결과:** BUILD SUCCESSFUL

---

### 8단계: 애플리케이션 실행 및 API 테스트

**애플리케이션 실행:**
```bash
./gradlew bootRun
```

**H2 Console 접속:**
1. 브라우저에서 `http://localhost:8080/h2-console` 접속
2. 연결 정보:
   - JDBC URL: `jdbc:h2:mem:testdb`
   - Username: `sa`
   - Password: `sa` (또는 비워두기)

**상품 데이터 준비:**
```sql
INSERT INTO products (product_id, name, price, stock, created_at, updated_at) 
VALUES ('product-001', '테스트 상품', 10000, 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
```

**API 테스트 - 주문 생성 성공:**
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

**예상 응답 (201 Created):**
```json
{
  "id": 1,
  "customerId": "customer-001",
  "productId": "product-001",
  "quantity": 2,
  "totalPrice": 20000,
  "status": "PENDING",
  "createdAt": "2026-01-26T14:30:00",
  "updatedAt": "2026-01-26T14:30:00"
}
```

**API 테스트 - 재고 부족 (400 Bad Request):**
```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "customer-001",
    "productId": "product-001",
    "quantity": 100,
    "totalPrice": 1000000
  }'
```

**예상 응답:**
```json
{
  "timestamp": "2026-01-26T14:30:00",
  "status": 400,
  "error": "Insufficient Stock",
  "message": "재고가 부족합니다. 상품: product-001, 요청 수량: 100, 현재 재고: 10"
}
```

**API 테스트 - 상품 없음 (404 Not Found):**
```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "customer-001",
    "productId": "product-999",
    "quantity": 1,
    "totalPrice": 10000
  }'
```

**예상 응답:**
```json
{
  "timestamp": "2026-01-26T14:30:00",
  "status": 404,
  "error": "Product Not Found",
  "message": "상품을 찾을 수 없습니다: product-999"
}
```

---

## 핵심 개념

### 1. 트랜잭션 관리 (ACID)

**@Transactional:**
- 전체 작업을 하나의 트랜잭션으로 처리
- 모든 작업이 성공하거나 모두 실패 (롤백)
- 재고 차감과 주문 생성이 원자적으로 처리

**비관적 락:**
- `@Lock(LockModeType.PESSIMISTIC_WRITE)`: SELECT FOR UPDATE
- 동시 주문 시 순차 처리
- 재고 중복 차감 방지

### 2. 비즈니스 로직 순서

**처리 순서 (중요!):**
1. 상품 조회 (비관적 락)
2. 재고 확인
3. 재고 차감
4. 주문 생성
5. 주문 저장

**순서가 중요한 이유:**
- 재고 확인 전에 차감하면 안 됨
- 주문 생성 전에 재고 차감해야 함
- 트랜잭션으로 전체 작업 보장

### 3. 예외 처리 전략

**Custom Exception 계층:**
```
OrderException
├── ProductNotFoundException (404)
└── InsufficientStockException (400)
```

**@RestControllerAdvice:**
- 전역 예외 처리
- 일관된 에러 응답 형식
- HTTP 상태 코드 자동 매핑

### 4. 단위 테스트 (Given-When-Then)

**테스트 패턴:**
- **Given**: 테스트 데이터 준비, Mock 설정
- **When**: 테스트 실행
- **Then**: 결과 검증, Mock 호출 확인

**Mockito 사용:**
- `@Mock`: Mock 객체 생성
- `@InjectMocks`: Mock 주입
- `when().thenReturn()`: Mock 동작 정의
- `verify()`: 메서드 호출 확인

---

## 프로젝트 구조

```
src/main/java/com/ccommit/monolith_to_msa/
├── domain/
│   ├── order/
│   │   ├── Order.java
│   │   └── OrderStatus.java
│   └── product/
│       └── Product.java
├── repository/
│   ├── order/
│   │   └── OrderRepository.java
│   └── product/
│       └── ProductRepository.java
├── service/
│   └── order/
│       ├── OrderService.java
│       └── OrderServiceImpl.java
├── controller/
│   └── order/
│       └── OrderController.java
├── dto/
│   └── order/
│       ├── OrderCreateRequest.java
│       └── OrderResponse.java
└── exception/
    ├── OrderException.java
    ├── ProductNotFoundException.java
    ├── InsufficientStockException.java
    └── GlobalExceptionHandler.java

src/test/java/com/ccommit/monolith_to_msa/
├── service/
│   └── order/
│       └── OrderServiceTest.java
└── controller/
    └── order/
        └── OrderControllerTest.java
```

---

## 실습 체크리스트

- [ ] 1단계: Product 엔티티 확인
- [ ] 2단계: ProductRepository 확인
- [ ] 3단계: OrderService 구현 확인
- [ ] 4단계: Custom Exception 확인
- [ ] 5단계: GlobalExceptionHandler 확인
- [ ] 6단계: 단위 테스트 확인
- [ ] 7단계: 빌드 및 테스트 실행
- [ ] 8단계: 애플리케이션 실행 및 API 테스트

---

## 문제 해결

### 트랜잭션 롤백 확인

**로그 설정:**
```yaml
logging:
  level:
    org.springframework.transaction: DEBUG
    org.hibernate.SQL: DEBUG
```

**확인 사항:**
- 예외 발생 시 재고 차감이 롤백되는지 확인
- 트랜잭션 로그 확인

### 동시성 문제

**비관적 락 확인:**
- `@Lock(LockModeType.PESSIMISTIC_WRITE)` 적용 확인
- 동시 주문 시 순차 처리 확인

### 테스트 실패

**Mock 설정 확인:**
- `when().thenReturn()` 올바르게 설정되었는지
- `verify()` 호출 횟수 확인

---

## 참고 자료

- **시퀀스 다이어그램:** `issue4.puml` 파일 참조
- **상세 개념 설명:** 각 단계별 파일 확인
