# Ch06.03: 도메인 모델링 실습 가이드

## 실습 목표
JPA를 활용한 도메인 모델 설계 및 영속성 컨텍스트 이해

## 핵심 개념

### 1. 엔티티 설계 (@Entity)

**Order 엔티티:**
- `@Entity`: JPA 엔티티로 지정
- `@Table(name = "orders")`: 테이블명 지정
- `@Id`, `@GeneratedValue`: 기본키 설정
- `@Column`: 컬럼 제약조건 설정
- `@Enumerated`: Enum 타입 매핑
- `@OneToMany`: 일대다 관계 설정

**Payment 엔티티:**
- `@ManyToOne`: 다대일 관계 설정
- `@JoinColumn`: 외래키 컬럼명 지정
- `@PrePersist`, `@PreUpdate`: 생명주기 콜백

### 2. 관계 설정

**@OneToMany (Order → Payment):**
```java
@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
private List<Payment> payments = new ArrayList<>();
```

**@ManyToOne (Payment → Order):**
```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "order_id", nullable = false)
private Order order;
```

**관계 설정 옵션:**
- `mappedBy`: 양방향 관계에서 연관관계의 주인 지정
- `cascade`: 영속성 전이 (부모 저장 시 자식도 함께 저장)
- `orphanRemoval`: 고아 객체 제거 (부모 삭제 시 자식도 삭제)
- `fetch`: 로딩 전략 (LAZY: 지연 로딩, EAGER: 즉시 로딩)

### 3. ERD (Entity Relationship Diagram)

**엔티티 관계:**
```
Order (1) ────< (N) Payment
```

**관계 설명:**
- 하나의 Order는 여러 개의 Payment를 가질 수 있음
- 하나의 Payment는 하나의 Order에만 속함
- Payment는 Order의 외래키(order_id)를 가짐

### 4. DDL 스크립트

**제약조건:**
- PRIMARY KEY: 기본키
- FOREIGN KEY: 외래키
- UNIQUE: 유일성 제약
- CHECK: 값 검증 제약
- INDEX: 성능 최적화

**테이블 구조:**
- `orders`: 주문 정보
- `payments`: 결제 정보

### 5. 영속성 컨텍스트

**1차 캐시:**
- 엔티티를 조회하면 영속성 컨텍스트에 저장
- 같은 엔티티를 다시 조회하면 1차 캐시에서 반환
- 동일성 보장 (같은 인스턴스 반환)

**변경 감지 (Dirty Checking):**
- 영속 상태의 엔티티를 수정하면 자동으로 감지
- 트랜잭션 커밋 시 UPDATE 쿼리 자동 실행
- 명시적 save() 호출 불필요

**쓰기 지연 (Write Behind):**
- 엔티티를 저장해도 즉시 INSERT하지 않음
- 쓰기 지연 SQL 저장소에 쿼리 저장
- 트랜잭션 커밋 시점에 한 번에 실행

## 프로젝트 구조

```
src/main/java/com/ccommit/monolith_to_msa/
├── domain/
│   ├── order/
│   │   ├── Order.java          # 주문 엔티티
│   │   └── OrderStatus.java    # 주문 상태 Enum
│   └── payment/
│       ├── Payment.java        # 결제 엔티티
│       ├── PaymentStatus.java   # 결제 상태 Enum
│       └── PaymentMethod.java  # 결제 방법 Enum
└── service/
    └── persistence/
        └── PersistenceContextExample.java  # 영속성 컨텍스트 예제

src/main/resources/
└── db/
    ├── migration/
    │   ├── V1__create_orders_table.sql
    │   └── V2__create_payments_table.sql
    └── schema.sql
```

## 실습 순서

### 1단계: 프로젝트 빌드 및 확인

**빌드 확인:**
```bash
./gradlew clean build
```

**컴파일 확인:**
```bash
./gradlew compileJava
```

**확인 사항:**
- Order 엔티티 컴파일 성공
- Payment 엔티티 컴파일 성공
- 관계 설정 오류 없음

### 2단계: Order 엔티티 확인 및 이해

**파일 위치:**
```
src/main/java/com/ccommit/monolith_to_msa/domain/order/Order.java
```

**주요 어노테이션 확인:**
- `@Entity`: JPA 엔티티 지정
- `@Table(name = "orders")`: 테이블명 지정
- `@Id`, `@GeneratedValue`: 기본키 설정
- `@OneToMany`: Payment와의 관계 설정

**주요 필드 확인:**
- `id`: 기본키 (BIGINT, AUTO_INCREMENT)
- `customerId`: 고객 ID (VARCHAR, NOT NULL)
- `productId`: 상품 ID (VARCHAR, NOT NULL)
- `quantity`: 수량 (INT, NOT NULL)
- `totalPrice`: 총 가격 (BIGINT, NOT NULL)
- `status`: 주문 상태 (Enum, NOT NULL)
- `payments`: 결제 목록 (OneToMany 관계)

**주요 메서드 확인:**
- `updateStatus(OrderStatus status)`: 주문 상태 변경
- `cancel()`: 주문 취소 (비즈니스 로직 포함)
- `addPayment(Payment payment)`: 결제 추가
- `getTotalPaymentAmount()`: 총 결제 금액 계산
- `isFullyPaid()`: 완전 결제 여부 확인

**실습:**
```bash
# Order.java 파일 열어서 확인
cat src/main/java/com/ccommit/monolith_to_msa/domain/order/Order.java
```

### 3단계: Payment 엔티티 확인 및 이해

**파일 위치:**
```
src/main/java/com/ccommit/monolith_to_msa/domain/payment/Payment.java
```

**주요 어노테이션 확인:**
- `@Entity`: JPA 엔티티 지정
- `@Table(name = "payments")`: 테이블명 지정
- `@ManyToOne`: Order와의 관계 설정
- `@JoinColumn`: 외래키 컬럼명 지정
- `@PrePersist`, `@PreUpdate`: 생명주기 콜백

**주요 필드 확인:**
- `id`: 기본키 (BIGINT, AUTO_INCREMENT)
- `order`: 주문 (ManyToOne 관계, LAZY 로딩)
- `amount`: 결제 금액 (BIGINT, NOT NULL)
- `method`: 결제 방법 (Enum, NOT NULL)
- `status`: 결제 상태 (Enum, NOT NULL)
- `transactionId`: 거래 ID (VARCHAR, UNIQUE)

**주요 메서드 확인:**
- `complete(String transactionId)`: 결제 완료
- `fail()`: 결제 실패
- `refund()`: 환불
- `isCompleted()`: 완료 여부 확인

**실습:**
```bash
# Payment.java 파일 열어서 확인
cat src/main/java/com/ccommit/monolith_to_msa/domain/payment/Payment.java
```

### 4단계: 관계 설정 확인 및 이해

**Order → Payment (OneToMany) 확인:**
```java
@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
private List<Payment> payments = new ArrayList<>();
```

**설명:**
- `mappedBy = "order"`: Payment 엔티티의 `order` 필드가 연관관계의 주인
- `cascade = CascadeType.ALL`: Order 저장/수정/삭제 시 Payment도 함께 처리
- `orphanRemoval = true`: Order에서 Payment 제거 시 DB에서도 삭제

**Payment → Order (ManyToOne) 확인:**
```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "order_id", nullable = false)
private Order order;
```

**설명:**
- `fetch = FetchType.LAZY`: 지연 로딩 (필요할 때만 조회)
- `@JoinColumn(name = "order_id")`: 외래키 컬럼명 지정
- `nullable = false`: 필수 관계

**실습:**
```bash
# 관계 설정 코드 확인
grep -A 2 "@OneToMany\|@ManyToOne" src/main/java/com/ccommit/monolith_to_msa/domain/**/*.java
```

### 5단계: ERD 다이어그램 확인

**ERD 파일:**
```
docs/issue3.puml
```

**관계 확인:**
- Order (1) ────< (N) Payment
- 하나의 Order는 여러 Payment를 가질 수 있음
- 하나의 Payment는 하나의 Order에만 속함

**실습:**
```bash
# PlantUML 파일 확인
cat docs/issue3.puml | head -50
```

**ERD 확인 방법:**
1. PlantUML 플러그인으로 열기
2. 온라인 에디터 사용: http://www.plantuml.com/plantuml/uml/
3. VS Code PlantUML 확장 사용

### 6단계: DDL 스크립트 확인

**스크립트 위치:**
```
src/main/resources/db/schema.sql
src/main/resources/db/migration/V1__create_orders_table.sql
src/main/resources/db/migration/V2__create_payments_table.sql
```

**orders 테이블 확인:**
- 기본키: `id` (BIGINT, AUTO_INCREMENT)
- 인덱스: `customer_id`, `status`, `created_at`
- 제약조건: `quantity > 0`, `total_price >= 0`

**payments 테이블 확인:**
- 기본키: `id` (BIGINT, AUTO_INCREMENT)
- 외래키: `order_id` → `orders.id` (ON DELETE CASCADE)
- 인덱스: `order_id`, `status`, `transaction_id`
- 제약조건: `amount > 0`

**실습:**
```bash
# DDL 스크립트 확인
cat src/main/resources/db/schema.sql
```

### 7단계: 애플리케이션 실행 및 테이블 생성 확인

**애플리케이션 실행:**
```bash
./gradlew bootRun
```

**H2 Console 접속:**
1. 브라우저에서 `http://localhost:8080/h2-console` 접속
2. JDBC URL: `jdbc:h2:mem:testdb`
3. User Name: `sa`
4. Password: `sa`

**테이블 생성 확인:**
```sql
-- 테이블 목록 확인
SHOW TABLES;

-- Orders 테이블 구조 확인
DESC orders;

-- Payments 테이블 구조 확인
DESC payments;
```

### 8단계: 영속성 컨텍스트 예제 확인

**예제 파일 위치:**
```
src/main/java/com/ccommit/monolith_to_msa/service/persistence/PersistenceContextExample.java
```

**주요 예제 메서드:**
1. **1차 캐시 예제**: `firstLevelCacheExample()`
   - 같은 엔티티를 두 번 조회하면 두 번째는 1차 캐시에서 반환
   
2. **변경 감지 예제**: `dirtyCheckingExample()`
   - 엔티티 수정 시 자동으로 UPDATE 쿼리 실행
   
3. **쓰기 지연 예제**: `writeBehindExample()`
   - 여러 엔티티 저장 시 커밋 시점에 배치 실행
   
4. **영속성 전이 예제**: `cascadeExample()`
   - Order 저장 시 Payment도 함께 저장

**실습:**
```bash
# 예제 코드 확인
cat src/main/java/com/ccommit/monolith_to_msa/service/persistence/PersistenceContextExample.java
```

### 9단계: 실제 데이터 저장 및 조회 테스트

**테스트 코드 작성 (선택사항):**
```java
@SpringBootTest
@Transactional
class OrderPaymentTest {
    
    @Autowired
    private OrderRepository orderRepository;
    
    @Test
    void testSaveOrderWithPayment() {
        // Order 생성
        Order order = Order.builder()
                .customerId("customer-001")
                .productId("product-001")
                .quantity(2)
                .totalPrice(20000L)
                .build();
        
        // Payment 생성
        Payment payment = Payment.builder()
                .order(order)
                .amount(20000L)
                .method(PaymentMethod.CREDIT_CARD)
                .status(PaymentStatus.PENDING)
                .build();
        
        // 관계 설정
        order.addPayment(payment);
        
        // 저장 (Cascade로 Payment도 함께 저장)
        orderRepository.save(order);
        
        // 조회 확인
        Order savedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assert savedOrder.getPayments().size() == 1;
    }
}
```

**H2 Console에서 직접 테스트:**
```sql
-- Order 조회
SELECT * FROM orders;

-- Payment 조회
SELECT * FROM payments;

-- 조인 조회
SELECT o.id, o.customer_id, p.amount, p.status
FROM orders o
LEFT JOIN payments p ON o.id = p.order_id;
```

### 10단계: 영속성 컨텍스트 동작 확인

**로그 확인:**
```yaml
# application.yaml에 추가
logging:
  level:
    org.hibernate.SQL: DEBUG
    org.hibernate.type.descriptor.sql.BasicBinder: TRACE
```

**실행 후 확인:**
- 1차 캐시: 같은 ID 조회 시 SELECT 쿼리 1번만 실행
- 변경 감지: 엔티티 수정 시 UPDATE 쿼리 자동 실행
- 쓰기 지연: 여러 save() 호출 후 커밋 시점에 배치 실행
- 영속성 전이: Order 저장 시 Payment도 함께 INSERT


## ERD 다이어그램

> 상세 ERD는 `issue3.puml` 파일 참조

**엔티티 관계:**
- Order (1) ────< (N) Payment

**관계 특징:**
- 양방향 관계
- Payment가 연관관계의 주인 (외래키 관리)
- Order는 읽기 전용 (mappedBy)

## DDL 스크립트

### Orders 테이블

```sql
CREATE TABLE orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id VARCHAR(255) NOT NULL,
    product_id VARCHAR(255) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    total_price BIGINT NOT NULL CHECK (total_price >= 0),
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_customer_id (customer_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
);
```

### Payments 테이블

```sql
CREATE TABLE payments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    method VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    transaction_id VARCHAR(255) UNIQUE,
    paid_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) 
        REFERENCES orders(id) ON DELETE CASCADE,
    INDEX idx_order_id (order_id),
    INDEX idx_status (status),
    INDEX idx_transaction_id (transaction_id)
);
```

## 영속성 컨텍스트

### 1차 캐시

**동작 방식:**
1. 엔티티 조회 시 영속성 컨텍스트에 저장
2. 같은 엔티티를 다시 조회하면 1차 캐시에서 반환
3. DB 조회 없이 빠른 접근 가능

**예제:**
```java
@Transactional(readOnly = true)
public void firstLevelCacheExample() {
    Order order1 = orderRepository.findById(1L).orElseThrow();  // DB 조회
    Order order2 = orderRepository.findById(1L).orElseThrow();  // 1차 캐시에서 반환
    System.out.println(order1 == order2);  // true (동일성 보장)
}
```

### 변경 감지 (Dirty Checking)

**동작 방식:**
1. 엔티티를 조회하면 스냅샷 저장
2. 엔티티 수정 시 스냅샷과 비교
3. 변경 감지 시 UPDATE 쿼리 생성
4. 트랜잭션 커밋 시 실행

**예제:**
```java
@Transactional
public void dirtyCheckingExample(Long orderId) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    order.updateStatus(OrderStatus.CONFIRMED);  // 수정
    // save() 호출 불필요
    // 트랜잭션 커밋 시 자동으로 UPDATE 실행
}
```

### 쓰기 지연 (Write Behind)

**동작 방식:**
1. 엔티티 저장 시 즉시 INSERT하지 않음
2. 쓰기 지연 SQL 저장소에 쿼리 저장
3. 트랜잭션 커밋 시점에 한 번에 실행
4. 배치 처리로 성능 최적화

**예제:**
```java
@Transactional
public void writeBehindExample() {
    orderRepository.save(order1);  // SQL 저장소에 저장
    orderRepository.save(order2);  // SQL 저장소에 저장
    // 트랜잭션 커밋 시 한 번에 INSERT 실행
}
```

### 영속성 전이 (Cascade)

**동작 방식:**
- 부모 엔티티를 저장하면 자식 엔티티도 함께 저장
- `CascadeType.ALL`: 모든 작업 전이
- `CascadeType.PERSIST`: 저장만 전이

**예제:**
```java
@Transactional
public void cascadeExample() {
    Order order = Order.builder()...build();
    Payment payment = Payment.builder()...build();
    order.addPayment(payment);
    
    orderRepository.save(order);  // Payment도 함께 저장
}
```

## 핵심 메시지 (4가지)

> 상세 다이어그램은 `issue3.puml` 파일 참조

### 1. 엔티티 설계 원칙
- `@Entity`로 엔티티 지정
- `@Table`로 테이블명 지정
- `@Column`으로 제약조건 설정
- 비즈니스 로직을 엔티티에 포함

### 2. 관계 설정 원칙
- `@OneToMany`, `@ManyToOne`으로 관계 설정
- 양방향 관계에서 연관관계의 주인 지정
- `cascade`로 영속성 전이 설정
- `fetch`로 로딩 전략 설정

### 3. 영속성 컨텍스트 활용
- 1차 캐시로 성능 최적화
- 변경 감지로 자동 UPDATE
- 쓰기 지연으로 배치 처리
- 영속성 전이로 편리한 저장

### 4. DDL 설계 원칙
- 제약조건으로 데이터 무결성 보장
- 인덱스로 조회 성능 최적화
- 외래키로 참조 무결성 보장
- CHECK 제약으로 값 검증

## 실습 체크리스트

실습 순서에 따른 체크리스트:

1. [x] 프로젝트 빌드 및 확인 (1단계)
2. [x] Order 엔티티 확인 및 이해 (2단계)
3. [x] Payment 엔티티 확인 및 이해 (3단계)
4. [x] 관계 설정 확인 및 이해 (4단계)
5. [x] ERD 다이어그램 확인 (5단계)
6. [x] DDL 스크립트 확인 (6단계)
7. [ ] 애플리케이션 실행 및 테이블 생성 확인 (7단계)
8. [x] 영속성 컨텍스트 예제 확인 (8단계)
9. [ ] 실제 데이터 저장 및 조회 테스트 (9단계)
10. [ ] 영속성 컨텍스트 동작 확인 (10단계)

## 테스트 방법

### 1. 엔티티 저장 테스트

```java
@Transactional
public void testSaveOrder() {
    Order order = Order.builder()
            .customerId("customer-001")
            .productId("product-001")
            .quantity(2)
            .totalPrice(20000L)
            .build();
    
    Payment payment = Payment.builder()
            .order(order)
            .amount(20000L)
            .method(PaymentMethod.CREDIT_CARD)
            .status(PaymentStatus.PENDING)
            .build();
    
    order.addPayment(payment);
    orderRepository.save(order);
}
```

### 2. 관계 조회 테스트

```java
@Transactional
public void testFindOrderWithPayments() {
    Order order = orderRepository.findById(1L).orElseThrow();
    List<Payment> payments = order.getPayments();  // LAZY 로딩
    // payments.size() 호출 시 SELECT 쿼리 실행
}
```

### 3. 영속성 컨텍스트 테스트

```java
@Transactional
public void testPersistenceContext() {
    // 1차 캐시 테스트
    Order order1 = orderRepository.findById(1L).orElseThrow();
    Order order2 = orderRepository.findById(1L).orElseThrow();
    assert order1 == order2;  // 동일성 보장
    
    // 변경 감지 테스트
    order1.updateStatus(OrderStatus.CONFIRMED);
    // save() 호출 없이도 UPDATE 실행
}
```

## 문제 해결

### 컴파일 오류
- `@Entity` 어노테이션 확인
- `@Id` 필수 확인
- 패키지 import 확인

### 관계 설정 오류
- `mappedBy` 값 확인
- 외래키 컬럼명 확인
- 양방향 관계에서 연관관계 주인 확인

### 영속성 컨텍스트 오류
- `@Transactional` 확인
- 엔티티 상태 확인 (영속/준영속/비영속)
- 트랜잭션 범위 확인

