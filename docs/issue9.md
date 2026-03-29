# Ch06.09 서비스 분리 실습 가이드

## 실습 목표
- `order-service`, `payment-service`를 독립 프로젝트로 분리
- 서비스별 독립 DB/Hikari 커넥션 풀 적용
- Order -> Payment 동기 REST 호출 구현
- `docker-compose-msa.yml`로 2개 서비스 동시 실행

---

## 0) 최종 디렉토리

```text
/Users/junshock5/Desktop/fastcampus/temp
├── order-service/
├── payment-service/
└── docker-compose-msa.yml
```

---

## 1) Order Service 생성 (코드 추가 순서)

### 1-1. Gradle 설정

`order-service/settings.gradle`
```gradle
rootProject.name = 'order-service'
```

`order-service/build.gradle`
```gradle
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.3.5'
    id 'io.spring.dependency-management' version '1.1.6'
}

group = 'com.ccommit'
version = '0.0.1-SNAPSHOT'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.boot:spring-boot-starter-aop'
    implementation 'org.springframework.retry:spring-retry'
    implementation 'org.flywaydb:flyway-core'
    runtimeOnly 'com.h2database:h2'
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

### 1-2. 애플리케이션 시작점

`order-service/src/main/java/com/ccommit/order/OrderServiceApplication.java`
```java
package com.ccommit.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@EnableRetry
@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
```

### 1-3. 도메인/리포지토리

추가 파일:
- `domain/Order.java`
- `domain/OrderStatus.java`
- `repository/OrderRepository.java`

**주요 파일 설명 (모놀리식 대비 포인트)**

| 파일 | 역할·변경 포인트 |
|------|------------------|
| `Order.java` | 주문 애그리거트. `customerId`, `productId`, `quantity`, `totalPrice`, `status` 및 타임스탬프만 보유. **Payment 엔티티·`@ManyToOne` 없음** — 결제는 별도 서비스 DB이므로 주문 쪽에서 결제 FK를 두지 않는다. `@PrePersist`/`@PreUpdate`로 생성·수정 시각을 채우고, `confirm()`으로 결제 성공 후 `CONFIRMED` 전이. |
| `OrderStatus.java` | 주문 상태 열거형. 실습 기준 `PENDING`(생성 직후) → `CONFIRMED`(원격 결제 완료 반영) → `CANCELLED`(취소). 필요 시 배송 등 단계를 추가해 확장한다. |
| `OrderRepository.java` | `JpaRepository<Order, Long>` 상속만 두어도 충분한 경우가 많다. 조회·필터 API를 늘리면 `findBy...` 메서드명 규칙 또는 `@Query`로 확장한다. |

### 1-4. DTO

추가 파일:
- `dto/OrderCreateRequest.java`
- `dto/OrderResponse.java`
- `dto/PaymentCreateRequest.java`
- `dto/PaymentResponse.java`

### 1-5. Payment Client + Service + Controller

추가 파일:
- `client/PaymentClient.java`
- `config/RestTemplateConfig.java`
- `service/OrderService.java`
- `service/OrderServiceImpl.java`
- `controller/OrderController.java`
- `exception/OrderNotFoundException.java`
- `exception/PaymentServiceException.java`
- `exception/GlobalExceptionHandler.java`

핵심 로직(`OrderServiceImpl`):
1. 주문 저장
2. `PaymentClient`로 결제 요청
3. 결제 상태가 `COMPLETED`면 주문 상태 `CONFIRMED` 변경

### 1-6. 설정/마이그레이션

`order-service/src/main/resources/application.yaml`
```yaml
server:
  port: 8080

spring:
  application:
    name: order-service

  datasource:
    url: jdbc:h2:mem:orderdb
    driver-class-name: org.h2.Driver
    username: sa
    password: sa
    hikari:
      maximum-pool-size: 20
      minimum-idle: 10
      connection-timeout: 30000

  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: true
    properties:
      hibernate:
        format_sql: true
        dialect: org.hibernate.dialect.H2Dialect

  flyway:
    enabled: true
    locations: classpath:db/migration

payment:
  service:
    url: ${PAYMENT_SERVICE_URL:http://localhost:8081}
```

`order-service/src/main/resources/db/migration/V1__create_orders_table.sql`
```sql
CREATE TABLE IF NOT EXISTS orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id VARCHAR(255) NOT NULL,
    product_id VARCHAR(255) NOT NULL,
    quantity INT NOT NULL,
    total_price BIGINT NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_orders_customer_id ON orders(customer_id);
CREATE INDEX idx_orders_status ON orders(status);
```

---

## 2) Payment Service 생성 (코드 추가 순서)

### 2-1. Gradle 설정

`payment-service/settings.gradle`
```gradle
rootProject.name = 'payment-service'
```

`payment-service/build.gradle`
```gradle
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.3.5'
    id 'io.spring.dependency-management' version '1.1.6'
}

group = 'com.ccommit'
version = '0.0.1-SNAPSHOT'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.flywaydb:flyway-core'
    runtimeOnly 'com.h2database:h2'
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

### 2-2. 애플리케이션 시작점

`payment-service/src/main/java/com/ccommit/payment/PaymentServiceApplication.java`
```java
package com.ccommit.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
```

### 2-3. 도메인/리포지토리/DTO

추가 파일:
- `domain/Payment.java`
- `domain/PaymentStatus.java`
- `domain/PaymentMethod.java`
- `repository/PaymentRepository.java`
- `dto/PaymentCreateRequest.java`
- `dto/PaymentResponse.java`

핵심 기준:
- `Payment`는 `orderId`만 보유
- Order 엔티티 직접 참조 없음

**주요 파일 설명**

| 파일 | 역할·변경 포인트 |
|------|------------------|
| `Payment.java` | 결제 애그리거트. **`Long orderId` 컬럼만** 두고 Order JPA 연관은 두지 않는다(타 서비스 식별자 참조). `amount`, `method`, `status`, `transactionId`, `paidAt`, 타임스탬프. `complete(transactionId)`에서 `COMPLETED`·`paidAt`·거래 ID 반영. |
| `PaymentStatus.java` | `PENDING` → `COMPLETED` 등 상태 열거. 실습 구현에 맞게 `FAILED` 등을 추가할 수 있다. |
| `PaymentMethod.java` | `CREDIT_CARD` 등 결제 수단. API JSON과 매핑되도록 enum 이름을 맞춘다. |
| `PaymentRepository.java` | `JpaRepository<Payment, Long>`. `orderId`·`status` 조회가 필요하면 `findByOrderId` 등으로 확장. |
| `PaymentCreateRequest.java` | Order 서비스가 넘기는 본문과 동일 스키마: `orderId`, `amount`, `method` + Bean Validation. |
| `PaymentResponse.java` | 클라이언트·Order 서비스가 역직렬화하는 응답 DTO. `from(Payment)` 정적 팩토리로 엔티티 → API 모델 변환. |

### 2-4. Service/Controller/Exception

추가 파일:
- `service/PaymentService.java`
- `service/PaymentServiceImpl.java`
- `controller/PaymentController.java`
- `exception/PaymentNotFoundException.java`
- `exception/GlobalExceptionHandler.java`

**주요 파일 설명**

| 파일 | 역할·변경 포인트 |
|------|------------------|
| `PaymentService.java` | `processPayment`, `getPayment` 등 유스케이스 인터페이스. 구현체는 트랜잭션·검증 정책을 담는다. |
| `PaymentServiceImpl.java` | `processPayment`: `PENDING`으로 빌드·저장 후 **`complete("TXN-" + UUID)`** 로 즉시 완료 처리(실습용 PG Mock). 영속 엔티티에 `complete` 호출 후 트랜잭션 커밋 시 DB 반영. `getPayment`: ID 조회 후 DTO 변환. |
| `PaymentController.java` | `POST /api/payments` → **201 CREATED**, `GET /api/payments/{id}` → 200. Order 서비스의 `RestTemplate`/`PaymentClient` 호출 URL과 경로가 일치해야 한다. |
| `PaymentNotFoundException.java` | 조회 실패 시 도메인 예외. |
| `GlobalExceptionHandler.java` | `@RestControllerAdvice`로 위 예외를 HTTP 상태·본문으로 매핑. API 오류 형식을 Order 쪽과 맞출지 팀 규약으로 정한다. |

핵심 로직(`PaymentServiceImpl`):
1. 요청으로 `Payment`를 `PENDING`으로 만들어 저장한다.
2. `complete(...)`로 **거래 ID**(예: `TXN-` + UUID)와 **`COMPLETED`**·`paidAt`을 설정한다.
3. `PaymentResponse.from`으로 응답을 만든다(HTTP 201은 Controller에서 지정).

### 2-5. 설정/마이그레이션

`payment-service/src/main/resources/application.yaml`
```yaml
server:
  port: 8081

spring:
  application:
    name: payment-service

  datasource:
    url: jdbc:h2:mem:paymentdb
    driver-class-name: org.h2.Driver
    username: sa
    password: sa
    hikari:
      maximum-pool-size: 20
      minimum-idle: 10
      connection-timeout: 30000

  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: true
    properties:
      hibernate:
        format_sql: true
        dialect: org.hibernate.dialect.H2Dialect

  flyway:
    enabled: true
    locations: classpath:db/migration
```

`payment-service/src/main/resources/db/migration/V1__create_payments_table.sql`
```sql
CREATE TABLE IF NOT EXISTS payments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    method VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    transaction_id VARCHAR(255),
    paid_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_payments_transaction_id UNIQUE (transaction_id)
);

CREATE INDEX idx_payments_order_id ON payments(order_id);
CREATE INDEX idx_payments_status ON payments(status);
```

---

## 3) Docker 파일 추가

**주요 추가·변경 파일:** 서비스별로 루트에 두 개의 `Dockerfile`만 두면 된다(호스트에 JDK/Gradle 없이 이미지 안에서 빌드).

| 경로 | 한 줄 요약 |
|------|------------|
| `order-service/Dockerfile` | Order 전용 이미지: 멀티 스테이지 빌드 → JRE 실행, 8080, 결제 URL은 Compose 서비스명 기준. |
| `payment-service/Dockerfile` | Payment 전용 이미지: 동일 패턴, 8081, 원격 Order 호출 없음. |

`order-service/Dockerfile`
```dockerfile
FROM gradle:8.14-jdk17 AS build
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY src ./src
RUN gradle clean bootJar -x test --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=default
ENV SPRING_DATASOURCE_URL=jdbc:h2:mem:orderdb
ENV PAYMENT_SERVICE_URL=http://payment-service:8081
ENTRYPOINT ["java", "-jar", "app.jar"]
```

`payment-service/Dockerfile`
```dockerfile
FROM gradle:8.14-jdk17 AS build
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY src ./src
RUN gradle clean bootJar -x test --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar
EXPOSE 8081
ENV SPRING_PROFILES_ACTIVE=default
ENV SPRING_DATASOURCE_URL=jdbc:h2:mem:paymentdb
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**주요 지시어 설명 (두 Dockerfile 공통)**

| 구간/지시어 | 설명 |
|-------------|------|
| `FROM gradle:8.14-jdk17 AS build` | Spring Boot Gradle 플러그인이 요구하는 **Gradle 8.14+** 이미지. `8.5` 등 낮은 버전이면 빌드 단계에서 플러그인 적용 실패할 수 있다. |
| `COPY build.gradle settings.gradle` → `COPY src` | Gradle 메타데이터 먼저 복사 후 소스 복사(의존성 캐시·레이어 재사용). |
| `RUN gradle clean bootJar -x test` | 컨테이너 안에서 실행 가능한 fat JAR 생성. `-x test`는 실습 빌드 시간 단축용. |
| `FROM eclipse-temurin:17-jre` | JDK 없이 JRE만 있는 런타임 이미지로 용량·공격 면적 감소. |
| `COPY --from=build ... *-SNAPSHOT.jar` | 빌드 스테이지 산출물만 최종 이미지로 복사. |
| `ENV SPRING_DATASOURCE_URL` | 컨테이너마다 H2 in-memory DB URL을 고정(주문/결제 DB 분리). |
| `ENV PAYMENT_SERVICE_URL` (order만) | **호스트명 `payment-service`** — Compose 기본 네트워크에서 서비스 이름이 DNS로 해석된다. |
| `EXPOSE` | 문서·도구용; 실제 포트 개방은 Compose `ports`가 담당. |

---

## 4) docker-compose-msa.yml

**주요 추가·변경 파일:** 저장소 루트의 `docker-compose-msa.yml` 한 개로 두 서비스의 빌드·포트·환경·네트워크를 묶는다.

루트 `docker-compose-msa.yml`:

```yaml
services:
  order-service:
    build:
      context: ./order-service
      dockerfile: Dockerfile
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=default
      - SPRING_DATASOURCE_URL=jdbc:h2:mem:orderdb
      - PAYMENT_SERVICE_URL=http://payment-service:8081
    networks:
      - msa-network
    depends_on:
      - payment-service

  payment-service:
    build:
      context: ./payment-service
      dockerfile: Dockerfile
    ports:
      - "8081:8081"
    environment:
      - SPRING_PROFILES_ACTIVE=default
      - SPRING_DATASOURCE_URL=jdbc:h2:mem:paymentdb
    networks:
      - msa-network

networks:
  msa-network:
    driver: bridge
```

**주요 키 설명**

| 키/블록 | 설명 |
|---------|------|
| `services.order-service.build.context` | `./order-service` — 해당 디렉터리가 빌드 컨텍스트이며, 그 안의 `Dockerfile`이 `COPY` 경로 기준이 된다. |
| `services.order-service.build.dockerfile` | 생략 시 기본 `Dockerfile`. 명시하면 의도가 분명해진다. |
| `services.payment-service.build` | `context: ./payment-service` + `dockerfile: Dockerfile` — 결제 서비스 이미지 전용 빌드. |
| `ports: "8080:8080"` / `"8081:8081"` | 호스트에서 각각 Order·Payment 애플리케이션 포트로 접속. |
| `environment` | 컨테이너 프로세스 환경 변수. Spring은 `SPRING_DATASOURCE_URL`, `PAYMENT_SERVICE_URL` 등을 설정으로 매핑한다(`application.yaml`보다 우선). |
| `depends_on` | 컨테이너 **시작 순서**만 조정한다. **payment가 준비 완료될 때까지 기다리지는 않는다**(헬스 기반 `condition` 없으면). |
| `networks` + `driver: bridge` | 사용자 정의 네트워크에 올린 컨테이너끼리 **서비스 이름(`order-service`, `payment-service`)으로 통신**할 수 있다. |
| `payment-service` | Order를 HTTP로 부르지 않으므로 `PAYMENT_SERVICE_URL`은 없다. 두 서비스 모두 같은 `msa-network`에 있어야 order → payment 이름 해석이 된다. |

> 저장소 루트의 `docker-compose-msa.yml`은 위와 동일한 구조이며, 필요 시 `deploy.resources` 등이 추가돼 있을 수 있다. Compose v2에서는 `version:` 키는 생략해도 된다.

---

## 5) 실행 순서

**전제:** 터미널 작업 디렉터리는 **`docker-compose-msa.yml`이 있는 저장소 루트** (예: 클론한 `monolith-to-msa` 폴더). Mac/Linux 예시는 `cd` 경로만 본인 환경에 맞게 바꾼다.

### 5-1. Docker Compose 실행 (권장)

```bash
cd /path/to/monolith-to-msa
docker compose -f docker-compose-msa.yml up -d --build
```

**포트 충돌:** `Bind for 0.0.0.0:8081 failed: port is already allocated` 가 나오면 기존에 8080/8081을 쓰는 컨테이너나 프로세스를 먼저 중지한다.

```bash
docker ps
docker stop <컨테이너이름 또는 ID>
```

### 5-2. 상태 확인

```bash
docker compose -f docker-compose-msa.yml ps
```

헬스(로컬에서 컨테이너 포트로 접근):

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8081/actuator/health
```

- **Windows PowerShell:** `curl`은 `Invoke-WebRequest` 별칭일 수 있으므로 **`curl.exe`** 를 쓴다.
- 응답이 `{"status":"UP"}` 이거나 집계 헬스면 정상에 가깝다. (일부 환경에서는 `DOWN` 집계가 나와도 비즈니스 API는 동작할 수 있음.)

### 5-3. 주문 생성 테스트

`POST /api/orders` 는 주문 저장 후 **payment-service** 로 결제 REST 를 호출하고, 성공 시 주문 상태가 **CONFIRMED** 로 내려온다.

**맥 / Linux / Git Bash (여러 줄):**

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "C100",
    "productId": "P100",
    "quantity": 1,
    "totalPrice": 15000
  }'
```

**한 줄 JSON (공통):**

```bash
curl.exe -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d "{\"customerId\":\"C100\",\"productId\":\"P100\",\"quantity\":1,\"totalPrice\":15000}"
```

**Windows PowerShell (권장):**

```powershell
$body = '{"customerId":"C100","productId":"P100","quantity":1,"totalPrice":15000}'
Invoke-RestMethod -Uri http://localhost:8080/api/orders -Method Post -ContentType "application/json" -Body $body
```

**JSON 파일로 전송 (저장소에 샘플 포함):**

```bash
curl.exe -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  --data-binary "@docs/order-create-payload.json"
```

### 5-4. 종료

```bash
docker compose -f docker-compose-msa.yml down
```

---

## 6) 누락/체크 포인트

- [x] `order-service`, `payment-service` 독립 `build.gradle`
- [x] 서비스별 독립 `application.yaml`
- [x] 서비스별 독립 Flyway migration
- [x] Order -> Payment REST 호출
- [x] `docker-compose-msa.yml`에서 서비스별 build context 분리
- [x] 결제 서비스가 주문 엔티티 직접 참조하지 않음 (`orderId`만 사용)
