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

### 2-4. Service/Controller/Exception

추가 파일:
- `service/PaymentService.java`
- `service/PaymentServiceImpl.java`
- `controller/PaymentController.java`
- `exception/PaymentNotFoundException.java`
- `exception/GlobalExceptionHandler.java`

핵심 로직(`PaymentServiceImpl`):
1. 결제 `PENDING` 저장
2. 트랜잭션 ID 생성
3. 결제 상태 `COMPLETED` 변경 후 응답

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

---

## 4) docker-compose-msa.yml

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
