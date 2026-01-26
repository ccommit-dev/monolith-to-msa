# Ch06.02: 모놀리식 설계 실습 가이드

## 실습 목표
레이어드 아키텍처를 기반으로 한 모놀리식 애플리케이션 설계 및 구현

## 핵심 개념

### 1. 레이어드 아키텍처 (Layered Architecture)

**계층 구조:**
```
Controller Layer (표현 계층)
    ↓
Service Layer (비즈니스 로직 계층)
    ↓
Repository Layer (데이터 접근 계층)
    ↓
Domain Layer (도메인 모델)
```

**각 계층의 역할:**
- **Controller**: HTTP 요청/응답 처리, 입력 검증
- **Service**: 비즈니스 로직 처리, 트랜잭션 관리
- **Repository**: 데이터베이스 접근, CRUD 작업
- **Domain**: 엔티티, 값 객체, 도메인 로직

### 2. 컴포넌트 설계 (Controller → Service → Repository)

**단방향 의존성:**
- Controller → Service (인터페이스)
- Service → Repository (인터페이스)
- 모든 계층 → Domain

**장점:**
- 계층 간 결합도 감소
- 테스트 용이성 향상
- 유지보수성 개선

### 3. 모듈 의존성 (단방향, DIP)

**DIP (Dependency Inversion Principle):**
- 고수준 모듈은 저수준 모듈에 의존하지 않아야 함
- 둘 다 추상화(인터페이스)에 의존해야 함

**구현 방식:**
- Service는 Repository 인터페이스에 의존
- Controller는 Service 인터페이스에 의존
- 구현체는 런타임에 주입 (의존성 주입)

### 4. 패키지 구조 (Domain 중심)

**패키지 구조:**
```
com.ccommit.monolith_to_msa/
├── domain/
│   └── order/
│       ├── Order.java          # 엔티티
│       └── OrderStatus.java    # Enum
├── repository/
│   └── order/
│       └── OrderRepository.java # Repository 인터페이스
├── service/
│   └── order/
│       ├── OrderService.java      # Service 인터페이스
│       └── OrderServiceImpl.java  # Service 구현체
├── controller/
│   └── order/
│       └── OrderController.java   # Controller
└── dto/
    └── order/
        ├── OrderCreateRequest.java # 요청 DTO
        └── OrderResponse.java      # 응답 DTO
```

**Domain 중심 설계의 장점:**
- 도메인 모델이 핵심
- 비즈니스 로직이 도메인에 집중
- 다른 계층은 도메인을 지원하는 역할

## 프로젝트 구조

```
src/main/java/com/ccommit/monolith_to_msa/
├── domain/
│   └── order/
│       ├── Order.java          # 주문 엔티티
│       └── OrderStatus.java    # 주문 상태 Enum
├── repository/
│   └── order/
│       └── OrderRepository.java # 주문 Repository
├── service/
│   └── order/
│       ├── OrderService.java      # 주문 Service 인터페이스
│       └── OrderServiceImpl.java  # 주문 Service 구현체
├── controller/
│   └── order/
│       └── OrderController.java   # 주문 Controller
└── dto/
    └── order/
        ├── OrderCreateRequest.java # 주문 생성 요청 DTO
        └── OrderResponse.java      # 주문 응답 DTO
```

## 실습 순서

### 1. Domain Layer 구현

**OrderStatus Enum 생성:**
- `PENDING`, `CONFIRMED`, `SHIPPED`, `DELIVERED`, `CANCELLED`

**Order Entity 생성:**
- JPA 엔티티로 구현
- 비즈니스 메서드 포함 (`updateStatus`, `cancel`)

**실행:**
```bash
./gradlew compileJava
```

### 2. Repository Layer 구현

**OrderRepository 인터페이스:**
- JpaRepository 상속
- 커스텀 쿼리 메서드 정의

**특징:**
- 인터페이스만 정의 (구현은 Spring Data JPA가 자동 생성)
- DIP 적용: Service는 이 인터페이스에 의존

### 3. Service Layer 구현

**OrderService 인터페이스:**
- 비즈니스 로직 메서드 정의
- DIP 적용: Controller는 이 인터페이스에 의존

**OrderServiceImpl 구현체:**
- Repository 인터페이스에 의존
- 트랜잭션 관리 (`@Transactional`)
- 비즈니스 로직 처리

### 4. Controller Layer 구현

**OrderController:**
- RESTful API 엔드포인트 제공
- Service 인터페이스에 의존
- 입력 검증 (`@Valid`)

### 5. DTO 클래스 생성

**OrderCreateRequest:**
- 주문 생성 요청 데이터
- Validation 어노테이션 적용

**OrderResponse:**
- 주문 응답 데이터
- Entity → DTO 변환 메서드 포함

### 6. 빌드 및 실행

```bash
# 빌드
./gradlew clean build

# 실행
./gradlew bootRun
```

## API 엔드포인트

### 주문 생성
```bash
POST /api/orders
Content-Type: application/json

{
  "customerId": "customer-001",
  "productId": "product-001",
  "quantity": 2,
  "totalPrice": 20000
}
```

### 주문 조회 (ID)
```bash
GET /api/orders/{id}
```

### 주문 조회 (고객 ID)
```bash
GET /api/orders/customers/{customerId}
```

### 주문 조회 (상태)
```bash
GET /api/orders/status/{status}
```

### 주문 상태 업데이트
```bash
PATCH /api/orders/{id}/status?status=CONFIRMED
```

### 주문 취소
```bash
DELETE /api/orders/{id}/customers/{customerId}
```

## 시퀀스 다이어그램

### 주문 생성 시퀀스

```
Client → Controller → Service → Repository → Database
  |         |          |          |            |
  |    POST /api/orders
  |         |          |          |            |
  |         |    createOrder()    |            |
  |         |          |          |            |
  |         |          |    save()             |
  |         |          |          |            |
  |         |          |    ← Order Entity      |
  |         |    ← OrderResponse   |            |
  |    ← 201 Created   |          |            |
  |         |          |          |            |
```

### 주문 조회 시퀀스

```
Client → Controller → Service → Repository → Database
  |         |          |          |            |
  |    GET /api/orders/{id}
  |         |          |          |            |
  |         |    getOrder(id)     |            |
  |         |          |          |            |
  |         |          |    findById(id)       |
  |         |          |          |            |
  |         |          |    ← Order Entity    |
  |         |    ← OrderResponse   |            |
  |    ← 200 OK        |          |            |
  |         |          |          |            |
```

## 핵심 메시지 (4가지)

> 상세 다이어그램은 `issue2.puml` 파일 참조

### 1. 단방향 의존성
- Controller → Service → Repository → Domain
- 역방향 의존성 없음
- 순환 참조 방지

### 2. 인터페이스 기반 설계 (DIP)
- Service와 Repository는 인터페이스로 정의
- 구현체는 런타임에 주입
- 테스트 시 Mock 객체 주입 가능

### 3. Domain 중심 설계
- Domain 모델이 핵심
- 비즈니스 로직은 Domain에 집중
- 다른 계층은 Domain을 지원

### 4. 계층별 책임 분리
- Controller: HTTP 처리
- Service: 비즈니스 로직
- Repository: 데이터 접근
- Domain: 도메인 모델
- **단일 책임 원칙 (SRP)**: 각 계층이 하나의 책임만 가짐
- **유지보수성**: 변경 영향 범위가 명확
- **테스트 용이성**: 각 계층을 독립적으로 테스트 가능
- **재사용성**: Service를 여러 Controller에서 재사용 가능
- **가독성**: 코드 구조가 명확하고 이해하기 쉬움

**책임 분리 체크리스트:**
- [ ] Controller에 비즈니스 로직이 있는가? → Service로 이동
- [ ] Service에서 HTTP 처리를 하는가? → Controller로 이동
- [ ] Repository에 비즈니스 로직이 있는가? → Service로 이동
- [ ] Domain이 단순 데이터 클래스인가? → 비즈니스 로직 추가

## 실습 체크리스트

1. [x] Domain Layer 구현 (Order, OrderStatus)
2. [x] Repository Layer 구현 (OrderRepository)
3. [x] Service Layer 구현 (OrderService, OrderServiceImpl)
4. [x] Controller Layer 구현 (OrderController)
5. [x] DTO 클래스 생성 (OrderCreateRequest, OrderResponse)
6. [x] 빌드 및 컴파일 확인
7. [x] API 테스트 (Postman 또는 curl)

## 테스트 방법

### 1. 주문 생성 테스트
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

### 2. 주문 조회 테스트
```bash
# 주문 ID로 조회
curl http://localhost:8080/api/orders/1

# 고객 ID로 조회
curl http://localhost:8080/api/orders/customers/customer-001

# 상태로 조회
curl http://localhost:8080/api/orders/status/PENDING
```

### 3. 주문 상태 업데이트 테스트
```bash
curl -X PATCH "http://localhost:8080/api/orders/1/status?status=CONFIRMED"
```

### 4. 주문 취소 테스트
```bash
curl -X DELETE http://localhost:8080/api/orders/1/customers/customer-001
```

## 문제 해결

### Graphviz 오류 (PlantUML)

**오류:** `cannot find graphviz` 또는 `Dot executable does not exist`

**해결 방법:**

**macOS (Homebrew 사용):**
```bash
# Graphviz 설치
brew install graphviz

# 설치 확인
dot -V
```

**설치 후 확인:**
```bash
# dot 실행 파일 위치 확인
which dot

# 버전 확인
dot -V
```

**대안: Graphviz 없이 PlantUML 사용**
- 현재 `issue2.puml` 파일은 `allowmixing`과 `class`/`interface` 키워드를 사용하여 Graphviz 없이도 작동하도록 수정됨
- 시퀀스 다이어그램은 Graphviz가 필요 없음
- 컴포넌트 다이어그램도 `class` 키워드 사용 시 Graphviz 불필요

**PlantUML 온라인 에디터 사용:**
- http://www.plantuml.com/plantuml/uml/ 에서 파일 내용 붙여넣기
- Graphviz 설치 없이도 다이어그램 확인 가능

### 컴파일 오류
- 패키지 경로 확인
- import 문 확인
- Lombok 어노테이션 확인

### 런타임 오류
- 데이터베이스 연결 확인
- 트랜잭션 설정 확인
- Validation 오류 확인

### API 테스트 오류
- 요청 형식 확인 (Content-Type: application/json)
- 필수 필드 확인
- HTTP 메서드 확인

