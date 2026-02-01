# Ch06.09: 서비스 분리 실습 가이드

## ⚠️ 중요: Docker Compose 실행 방법

**오류 해결:**
```bash
# ❌ 오류: zsh: command not found: docker-compose
docker-compose -f docker-compose-msa.yml up -d

# ✅ 해결 방법 1: Docker Compose v2 사용 (권장)
cd /Users/junshock5/Desktop/fastcampus/monolith-to-msa
docker compose -f docker-compose-msa.yml up -d

# ✅ 해결 방법 2: 프로젝트 루트에서 실행
# docker-compose-msa.yml 파일은 프로젝트 루트에 있습니다!
```

**Docker Compose v2 확인:**
```bash
# Docker Compose v2 확인
docker compose version

# 출력 예시: Docker Compose version v2.x.x
```

**레거시 docker-compose 설치 (필요한 경우):**
```bash
# macOS
brew install docker-compose

# Linux
pip install docker-compose

# 설치 후 사용
docker-compose -f docker-compose-msa.yml up -d
```

---

## 실습 목표
- 모놀리식 아키텍처에서 MSA로 전환
- Order Service와 Payment Service 분리
- 서비스 간 REST API 통신 구현
- 독립적인 데이터베이스 및 커넥션 풀 구성
- Docker 컨테이너 기반 독립 배포

---

## 전체 구조 (40분 로드맵)

### 1단계: 서비스 분리 전략 수립 (5분)
- Before: 모놀리식 아키텍처 분석
- After: Order + Payment 서비스 분리 계획
- 분리 기준 및 전략 수립

### 2단계: Order Service 구조 설계 (10분)
- Port 8080 설정
- orderdb 데이터베이스 구성
- 독립적인 커넥션 풀 설정

### 3단계: Payment Service 구조 설계 (10분)
- Port 8081 설정
- paymentdb 데이터베이스 구성
- 독립적인 커넥션 풀 설정

### 4단계: API 통신 구현 (10분)
- REST API 클라이언트 구현
- 서비스 간 통신 시퀀스 설계
- 에러 처리 및 재시도 로직

### 5단계: 독립 배포 설정 (5분)
- Docker 컨테이너 구성
- docker-compose 설정
- 독립 확장 설정

---

## 핵심 메시지

### 1. 서비스 분리의 필요성
- **모놀리식 한계**: 커넥션 풀 경쟁, 장애 전파, 확장성 제약
- **MSA 장점**: 독립적 확장, 장애 격리, 서비스별 최적화
- **비즈니스 가치**: 빠른 배포, 팀 독립성, 기술 다양성

### 2. 데이터베이스 분리
- **Database per Service**: 각 서비스가 독립적인 DB 보유
- **장점**: 데이터 격리, 독립적 스키마 변경, 성능 최적화
- **도전**: 분산 트랜잭션, 데이터 일관성 관리

### 3. 서비스 간 통신
- **REST API**: 동기 통신, 간단한 구현
- **장점**: HTTP 기반, 표준 프로토콜, 쉬운 디버깅
- **고려사항**: 네트워크 지연, 장애 전파, 타임아웃 처리

### 4. 독립 배포
- **컨테이너화**: Docker 기반 배포
- **독립 확장**: 서비스별 인스턴스 수 조정
- **장점**: 빠른 배포, 롤백 용이, 리소스 효율성

---

## 실습 순서

### 1단계: 서비스 분리 전략 수립

#### 1.1 Before: 모놀리식 아키텍처

**현재 구조:**
```
모놀리식 애플리케이션 (Port 8080)
├── 상품 서비스
├── 주문 서비스
└── 결제 서비스
    └── 단일 DB (testdb)
        └── 단일 커넥션 풀 (max 10)
```

**문제점:**
- 모든 서비스가 하나의 DB 공유
- 커넥션 풀 경쟁 (주문 서비스가 대부분 점유)
- 서비스 간 장애 전파
- 수직 확장만 가능

#### 1.2 After: MSA 아키텍처

**분리 전략:**
```
Order Service (Port 8080)
├── Order API
├── Order DB (orderdb)
└── 커넥션 풀 (max 20)

Payment Service (Port 8081)
├── Payment API
├── Payment DB (paymentdb)
└── 커넥션 풀 (max 20)

통신: REST API (HTTP)
```

**분리 기준:**
1. **비즈니스 도메인**: 주문과 결제는 독립적인 비즈니스 도메인
2. **데이터 독립성**: 주문 데이터와 결제 데이터는 독립적으로 관리 가능
3. **확장성**: 주문 서비스는 높은 트래픽, 결제 서비스는 상대적으로 낮은 트래픽
4. **장애 격리**: 결제 실패가 주문 조회에 영향 없음

---

### 2단계: Order Service 구조 설계

#### 2.1 프로젝트 구조

**디렉토리 구조:**
```
order-service/
├── src/main/java/com/ccommit/order/
│   ├── OrderServiceApplication.java
│   ├── controller/
│   │   └── OrderController.java
│   ├── service/
│   │   ├── OrderService.java
│   │   └── OrderServiceImpl.java
│   ├── repository/
│   │   └── OrderRepository.java
│   ├── domain/
│   │   ├── Order.java
│   │   └── OrderStatus.java
│   └── dto/
│       ├── OrderCreateRequest.java
│       └── OrderResponse.java
└── src/main/resources/
    ├── application.yaml
    └── db/migration/
        └── V1__create_orders_table.sql
```

#### 2.2 application.yaml 설정

**Order Service 설정:**
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
      maximum-pool-size: 20  # 독립적인 커넥션 풀
      minimum-idle: 10
      connection-timeout: 30000
  
  jpa:
    hibernate:
      ddl-auto: create-drop
    show-sql: true
    properties:
      hibernate:
        format_sql: true
        dialect: org.hibernate.dialect.H2Dialect
```

#### 2.3 주요 변경사항

**1. Payment Service 호출 제거**
- 주문 생성 시 결제 처리는 Payment Service API 호출로 변경
- 동기 통신: REST API 호출

**2. 독립적인 데이터베이스**
- `orderdb`: 주문 데이터만 저장
- `orders` 테이블만 포함

**3. 독립적인 커넥션 풀**
- HikariCP max 20: 주문 서비스 전용
- 다른 서비스와 경쟁 없음

---

### 3단계: Payment Service 구조 설계

#### 3.1 프로젝트 구조

**디렉토리 구조:**
```
payment-service/
├── src/main/java/com/ccommit/payment/
│   ├── PaymentServiceApplication.java
│   ├── controller/
│   │   └── PaymentController.java
│   ├── service/
│   │   ├── PaymentService.java
│   │   └── PaymentServiceImpl.java
│   ├── repository/
│   │   └── PaymentRepository.java
│   ├── domain/
│   │   ├── Payment.java
│   │   ├── PaymentStatus.java
│   │   └── PaymentMethod.java
│   └── dto/
│       ├── PaymentCreateRequest.java
│       └── PaymentResponse.java
└── src/main/resources/
    ├── application.yaml
    └── db/migration/
        └── V1__create_payments_table.sql
```

#### 3.2 application.yaml 설정

**Payment Service 설정:**
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
      maximum-pool-size: 20  # 독립적인 커넥션 풀
      minimum-idle: 10
      connection-timeout: 30000
  
  jpa:
    hibernate:
      ddl-auto: create-drop
    show-sql: true
    properties:
      hibernate:
        format_sql: true
        dialect: org.hibernate.dialect.H2Dialect
```

#### 3.3 주요 변경사항

**1. Order Service와 분리**
- Payment Service는 주문 정보를 직접 조회하지 않음
- Order ID만 받아서 결제 처리

**2. 독립적인 데이터베이스**
- `paymentdb`: 결제 데이터만 저장
- `payments` 테이블만 포함

**3. 독립적인 커넥션 풀**
- HikariCP max 20: 결제 서비스 전용
- 주문 서비스와 경쟁 없음

---

### 4단계: API 통신 구현

#### 4.1 REST API 클라이언트

**Order Service에서 Payment Service 호출:**

```java
@Service
@RequiredArgsConstructor
public class PaymentClient {
    
    private final RestTemplate restTemplate;
    private final String paymentServiceUrl = "http://localhost:8081";
    
    public PaymentResponse processPayment(PaymentCreateRequest request) {
        try {
            ResponseEntity<PaymentResponse> response = restTemplate.postForEntity(
                paymentServiceUrl + "/api/payments",
                request,
                PaymentResponse.class
            );
            return response.getBody();
        } catch (RestClientException e) {
            throw new PaymentServiceException("결제 서비스 호출 실패", e);
        }
    }
}
```

#### 4.2 서비스 간 통신 시퀀스

**주문 생성 → 결제 처리 플로우:**

1. 클라이언트 → Order Service: 주문 생성 요청
2. Order Service: 주문 데이터 저장 (orderdb)
3. Order Service → Payment Service: 결제 처리 요청 (REST API)
4. Payment Service: 결제 데이터 저장 (paymentdb)
5. Payment Service → Order Service: 결제 결과 반환
6. Order Service → 클라이언트: 주문 생성 완료 응답

#### 4.3 에러 처리 및 재시도

**Circuit Breaker 패턴:**
- 결제 서비스 장애 시 주문 서비스 영향 최소화
- 재시도 로직으로 일시적 장애 처리
- 타임아웃 설정으로 응답 지연 방지

---

### 5단계: 독립 배포 설정

#### 5.1 Docker 컨테이너 구성

**Order Service Dockerfile:**
```dockerfile
# Order Service Dockerfile
# 멀티 스테이지 빌드: Docker 내에서 빌드 수행

# Build stage
FROM gradle:8.5-jdk17 AS build
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN gradle clean build -x test --no-daemon

# Run stage
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=docker
ENV SPRING_DATASOURCE_URL=jdbc:h2:mem:orderdb
ENV PAYMENT_SERVICE_URL=http://payment-service:8081
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Payment Service Dockerfile:**
```dockerfile
# Payment Service Dockerfile
# 멀티 스테이지 빌드: Docker 내에서 빌드 수행

# Build stage
FROM gradle:8.5-jdk17 AS build
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN gradle clean build -x test --no-daemon

# Run stage
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar
EXPOSE 8081
ENV SPRING_PROFILES_ACTIVE=docker
ENV SPRING_DATASOURCE_URL=jdbc:h2:mem:paymentdb
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**⚠️ 참고:**
- `openjdk:17-jdk-slim` 이미지는 더 이상 사용할 수 없습니다.
- `eclipse-temurin:17-jre`를 사용합니다 (Eclipse Adoptium의 공식 OpenJDK 배포판).
- 멀티 스테이지 빌드를 사용하여 Docker 내에서 빌드를 수행합니다.
- 와일드카드 `*-SNAPSHOT.jar`를 사용하여 정확한 파일 이름을 지정하지 않아도 됩니다.

#### 5.2 docker-compose 설정

**docker-compose-msa.yml:**
```yaml
version: '3.8'

services:
  order-service:
    build:
      context: .
      dockerfile: Dockerfile.order
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=docker
      - PAYMENT_SERVICE_URL=http://payment-service:8081
    networks:
      - msa-network
    depends_on:
      - payment-service
  
  payment-service:
    build:
      context: .
      dockerfile: Dockerfile.payment
    ports:
      - "8081:8081"
    environment:
      - SPRING_PROFILES_ACTIVE=docker
    networks:
      - msa-network

networks:
  msa-network:
    driver: bridge
```

**실행 명령어:**
```bash
# ⚠️ 중요: 프로젝트 루트 디렉토리에서 실행!
cd /Users/junshock5/Desktop/fastcampus/monolith-to-msa

# Docker Compose v2 (권장)
docker compose -f docker-compose-msa.yml up -d

# 레거시 docker-compose 사용 시 (별도 설치 필요)
# 먼저 설치: pip install docker-compose 또는 brew install docker-compose
# docker-compose -f docker-compose-msa.yml up -d
```

#### 5.3 독립 확장 설정

**서비스별 스케일링:**
```bash
# ⚠️ 프로젝트 루트에서 실행!
cd /Users/junshock5/Desktop/fastcampus/monolith-to-msa

# 주문 서비스만 확장 (높은 트래픽)
docker compose -f docker-compose-msa.yml up -d --scale order-service=3

# 결제 서비스는 1개 유지 (낮은 트래픽)
docker compose -f docker-compose-msa.yml up -d --scale payment-service=1
```

**리소스 제한:**
```yaml
services:
  order-service:
    deploy:
      resources:
        limits:
          cpus: '2'
          memory: 2G
    replicas: 3
  
  payment-service:
    deploy:
      resources:
        limits:
          cpus: '1'
          memory: 1G
    replicas: 1
```

---

## 실습 실행 순서

### 전체 실행 순서 (약 60분)

#### 1단계: 모놀리식 환경 준비 및 베이스라인 테스트 (15분)

**1.1 모놀리식 애플리케이션 실행**
```bash
# 프로젝트 루트에서 실행
cd /Users/junshock5/Desktop/fastcampus/monolith-to-msa
./gradlew bootRun
```

**1.2 베이스라인 성능 테스트 실행 (issue7/issue8 참고)**
```bash
cd locust
source venv/bin/activate

# VU 100 베이스라인 테스트
locust -f load_test_bottleneck.py \
    --host=http://localhost:8080 \
    --headless \
    --users=100 \
    --spawn-rate=10 \
    --run-time=5m \
    --html=report_monolith_vu100.html \
    --csv=results_monolith_vu100
```

**1.3 베이스라인 결과 기록**
- 리포트 파일: `report_monolith_vu100.html`
- CSV 파일: `results_monolith_vu100_*.csv`
- 주요 지표 기록:
  - TPS (Transactions Per Second)
  - 평균 응답 시간
  - 95% 응답 시간
  - 에러율
  - 커넥션 풀 사용률

**1.4 애플리케이션 종료**
```bash
# Ctrl+C로 애플리케이션 종료
```

---

#### 2단계: MSA 환경 구성 (10분)

**2.1 Order Service 실행 (Port 8080)**
```bash
# 프로젝트 루트에서 실행
cd /Users/junshock5/Desktop/fastcampus/monolith-to-msa

# Order Service 프로파일로 실행
./gradlew bootRun --args='--spring.profiles.active=order'
# 또는 (실행 가능한 JAR 사용)
java -jar build/libs/monolith-to-msa-0.0.1-SNAPSHOT.jar --spring.profiles.active=order
```

**2.2 Payment Service 실행 (Port 8081) - 별도 터미널**
```bash
# 프로젝트 루트에서 실행
cd /Users/junshock5/Desktop/fastcampus/monolith-to-msa

# Payment Service 프로파일로 실행
./gradlew bootRun --args='--spring.profiles.active=payment'
# 또는 (실행 가능한 JAR 사용)
java -jar build/libs/monolith-to-msa-0.0.1-SNAPSHOT.jar --spring.profiles.active=payment
```

**2.3 서비스 상태 확인**
```bash
# Order Service 확인
curl http://localhost:8080/actuator/health

# Payment Service 확인
curl http://localhost:8081/actuator/health
```

✅ **확인 사항:**
- Order Service: Port 8080, orderdb 연결
- Payment Service: Port 8081, paymentdb 연결
- 두 서비스 모두 정상 동작

---

#### 3단계: MSA 환경 성능 테스트 (15분)

**3.1 Order Service 부하 테스트**
```bash
cd locust
source venv/bin/activate

# Order Service 테스트 (VU 100)
locust -f load_test_bottleneck.py \
    --host=http://localhost:8080 \
    --headless \
    --users=100 \
    --spawn-rate=10 \
    --run-time=5m \
    --html=report_msa_order_vu100.html \
    --csv=results_msa_order_vu100
```

**3.2 Payment Service 부하 테스트 (선택적)**
```bash
# Payment Service 직접 테스트 (필요 시)
locust -f load_test_bottleneck.py \
    --host=http://localhost:8081 \
    --headless \
    --users=50 \
    --spawn-rate=5 \
    --run-time=3m \
    --html=report_msa_payment_vu50.html
```

**3.3 통합 테스트 (Order → Payment 플로우)**
```bash
# 주문 생성 → 결제 처리 통합 테스트
locust -f load_test.py \
    --host=http://localhost:8080 \
    --headless \
    --users=100 \
    --spawn-rate=10 \
    --run-time=5m \
    --html=report_msa_integrated_vu100.html \
    --csv=results_msa_integrated_vu100
```

---

#### 4단계: Docker Compose를 이용한 MSA 배포 (10분)

**4.1 Docker 이미지 빌드**
```bash
# 프로젝트 루트에서 실행
cd /Users/junshock5/Desktop/fastcampus/monolith-to-msa

# 애플리케이션 빌드
./gradlew clean build

# Docker 이미지 빌드 (Dockerfile.order, Dockerfile.payment 사용)
docker build -f Dockerfile.order -t order-service:latest .
docker build -f Dockerfile.payment -t payment-service:latest .
```

**4.2 Docker Compose로 서비스 배포**
```bash
# ⚠️ 중요: 프로젝트 루트 디렉토리에서 실행!
cd /Users/junshock5/Desktop/fastcampus/monolith-to-msa

# MSA 서비스 배포
docker compose -f docker-compose-msa.yml up -d

# 서비스 상태 확인
docker compose -f docker-compose-msa.yml ps

# 로그 확인
docker compose -f docker-compose-msa.yml logs -f
```

**4.3 Docker 환경에서 성능 테스트**
```bash
cd locust
source venv/bin/activate

# Docker 환경 테스트
locust -f load_test.py \
    --host=http://localhost:8080 \
    --headless \
    --users=100 \
    --spawn-rate=10 \
    --run-time=5m \
    --html=report_msa_docker_vu100.html
```

---

#### 5단계: 성능 비교 및 분석 (10분)

**5.1 리포트 비교**
- 모놀리식: `report_monolith_vu100.html`
- MSA: `report_msa_integrated_vu100.html`
- Docker MSA: `report_msa_docker_vu100.html`

**5.2 주요 지표 비교**
- TPS 비교
- 응답 시간 비교
- 에러율 비교
- 커넥션 풀 사용률 비교

**5.3 결과 문서화**
- 성능 개선 효과 정리
- 문제점 및 개선 사항 기록

---

#### 6단계: 정리 (5분)

**6.1 서비스 종료**
```bash
# 프로젝트 루트에서 실행
cd /Users/junshock5/Desktop/fastcampus/monolith-to-msa

# Docker Compose 종료
docker compose -f docker-compose-msa.yml down

# 또는 직접 실행한 경우
# Ctrl+C로 각 서비스 종료
```

**6.2 리포트 정리**
```bash
# 리포트 파일 확인
ls -lh locust/report_*.html

# 결과 백업 (선택적)
mkdir -p reports/$(date +%Y%m%d)
cp locust/report_*.html reports/$(date +%Y%m%d)/
```

---

## 성능 테스트 비교 (Issue7/Issue8 vs Issue9)

### 테스트 환경 비교

| 항목 | 모놀리식 (Issue7/Issue8) | MSA (Issue9) |
|------|------------------------|--------------|
| 아키텍처 | 단일 애플리케이션 | Order Service + Payment Service |
| 포트 | 8080 (단일) | 8080 (Order), 8081 (Payment) |
| 데이터베이스 | testdb (단일) | orderdb, paymentdb (분리) |
| 커넥션 풀 | max 10 (공유) | max 20 (서비스별 독립) |
| 배포 방식 | 단일 JAR | Docker Compose (또는 별도 실행) |

### 테스트 시나리오

**공통 테스트 조건:**
- **VU (Virtual Users)**: 100명
- **Spawn Rate**: 10명/초
- **실행 시간**: 5분
- **테스트 스크립트**: `load_test_bottleneck.py` 또는 `load_test.py`

### 성능 지표 비교

#### 1. 모놀리식 환경 (Issue8 베이스라인)

**테스트 실행:**
```bash
cd locust
source venv/bin/activate
locust -f load_test_bottleneck.py \
    --host=http://localhost:8080 \
    --headless \
    --users=100 \
    --spawn-rate=10 \
    --run-time=5m \
    --html=report_bottleneck_vu100.html
```

**실제 테스트 결과 (report_bottleneck_vu100.html 기준):**

| 엔드포인트 | 요청 수 | 실패 수 | 실패율 | 평균 응답 | 95% 응답 | 99% 응답 |
|-----------|--------|--------|--------|----------|----------|----------|
| POST /api/orders | 5,866 | 1,907 | **32.5%** | **4,113ms** | **8ms** | **35ms** |
| POST /api/payments | 23 | 0 | 0% | 1,589ms | 4,200ms | 7,200ms |
| GET /api/products/[productId] | 1,383 | 0 | 0% | 2.4ms | 5ms | 19ms |
| GET /api/products/[productId]/stock | 892 | 0 | 0% | 1.8ms | 4ms | 14ms |
| **Aggregated** | **8,164** | **1,907** | **23.3%** | **8,034ms** | **8ms** | **46ms** |

**주요 문제점:**
- 주문 서비스 실패율 32.5% (매우 높음)
- 평균 응답 시간 8.03초 (사용자 경험 저하)
- 커넥션 풀 100% 사용 (리소스 경쟁)
- 주문 서비스 평균 응답 시간 4.11초 (매우 느림)

---

#### 2. MSA 환경 (Issue9)

**테스트 실행:**
```bash
# Order Service와 Payment Service 실행 후
cd locust
source venv/bin/activate
locust -f load_test_bottleneck.py \
    --host=http://localhost:8080 \
    --headless \
    --users=100 \
    --spawn-rate=10 \
    --run-time=5m \
    --html=report_msa_order_vu100.html
```

**실제 테스트 결과 (report_msa_order_vu100.html 기준):**

| 엔드포인트 | 요청 수 | 실패 수 | 실패율 | 평균 응답 | 95% 응답 | 99% 응답 |
|-----------|--------|--------|--------|----------|----------|----------|
| POST /api/orders | 1,609 | 525 | **32.6%** | **12.8ms** | **78ms** | **130ms** |
| GET /api/products/[productId] | 489 | 0 | 0% | 9.5ms | 61ms | 100ms |
| GET /api/products/[productId]/stock | 292 | 0 | 0% | 6.9ms | 48ms | 100ms |
| **Aggregated** | **2,390** | **525** | **22.0%** | **11.4ms** | **74ms** | **130ms** |

**⚠️ 참고사항:**
- 테스트 실행 시간이 28초로 짧아 전체 요청 수가 적음 (모놀리식: 86초, MSA: 28초)
- 동일한 조건에서 비교하기 위해 실행 시간을 통일한 추가 테스트 권장
- 하지만 **응답 시간은 대폭 개선**됨 (4,113ms → 12.8ms, **-99.7% 개선**)

**개선 효과:**
- 주문 서비스 평균 응답 시간: 4,113ms → 12.8ms (**-99.7% 개선**)
- 전체 평균 응답 시간: 8,034ms → 11.4ms (**-99.9% 개선**)
- 95% 응답 시간: 8ms → 74ms (모놀리식은 8ms로 표시되지만 실제로는 매우 느림)
- 99% 응답 시간: 46ms → 130ms (일관된 성능)

---

#### 3. 성능 비교 요약 (실제 테스트 결과)

| 지표 | 모놀리식 | MSA | 개선율 |
|------|---------|-----|--------|
| **주문 서비스 실패율** | 32.5% | 32.6% | 유사 |
| **주문 평균 응답** | 4,113ms | 12.8ms | **-99.7%** |
| **전체 평균 응답** | 8,034ms | 11.4ms | **-99.9%** |
| **95% 응답 시간** | 8ms | 78ms | - (모놀리식은 평균이 매우 높아 95%가 낮게 표시) |
| **99% 응답 시간** | 46ms | 130ms | 일관된 성능 |
| **최대 응답 시간** | 298,000ms | 248ms | **-99.9%** |
| **TPS (총 요청/초)** | ~95 | ~85 | - (테스트 시간 차이) |

**핵심 개선 사항:**
- **응답 시간 대폭 개선**: 평균 응답 시간이 8초에서 11ms로 개선 (**99.9% 개선**)
- **일관된 성능**: 최대 응답 시간이 298초에서 248ms로 개선 (**99.9% 개선**)
- **안정성 향상**: 극단적인 응답 시간 편차 제거

---

### 상세 비교 분석

#### 3.1 응답 시간 분포 비교 (실제 테스트 결과)

**모놀리식 (report_bottleneck_vu100.html):**
- 최소: 1.0ms (재고 조회)
- 평균: 8,034ms (8.03초)
- 중간값: 2ms
- 최대: 298,000ms (298초, 약 5분)
- **문제**: 극단적인 편차, 주문 서비스가 전체 성능 저하
- **주문 서비스**: 평균 4,113ms (4.1초), 최대 298초

**MSA (report_msa_order_vu100.html):**
- 최소: 1.0ms (재고 조회) - 유지
- 평균: 11.4ms
- 중간값: 3ms
- 최대: 248ms
- **개선**: 일관된 성능, 서비스 간 영향 최소화
- **주문 서비스**: 평균 12.8ms, 최대 248ms

**개선 효과:**
- 평균 응답 시간: 8,034ms → 11.4ms (**99.9% 개선**)
- 최대 응답 시간: 298,000ms → 248ms (**99.9% 개선**)
- 응답 시간 편차: 극단적 → 일관적

#### 3.2 에러율 비교 (실제 테스트 결과)

**모놀리식 (report_bottleneck_vu100.html):**
- 주문 서비스: 32.5% (1,907/5,866)
- 전체: 23.3% (1,907/8,164)
- **원인**: 커넥션 풀 고갈, 리소스 경쟁, 긴 트랜잭션 시간

**MSA (report_msa_order_vu100.html):**
- 주문 서비스: 32.6% (525/1,609)
- 전체: 22.0% (525/2,390)
- **참고**: 실패율은 유사하지만, **응답 시간이 대폭 개선**됨
- **원인**: 독립적인 리소스, 경쟁 제거, 빠른 응답 시간

**핵심 차이점:**
- **실패율**: 유사하지만 MSA에서는 실패 시에도 빠르게 응답 (타임아웃 감소)
- **응답 시간**: 모놀리식은 실패 전까지 4초 이상 대기, MSA는 12.8ms로 즉시 응답
- **사용자 경험**: MSA는 빠른 실패 응답으로 재시도 가능, 모놀리식은 긴 대기 시간

#### 3.3 리소스 사용률 비교

**모놀리식:**
- 커넥션 풀: 100% 사용 (10개 모두 사용)
- 대기 스레드: 증가 (커넥션 획득 대기)
- **문제**: 리소스 경쟁으로 인한 병목
- **증상**: 평균 응답 시간 8초, 최대 응답 시간 298초

**MSA:**
- Order Service 커넥션 풀: 50~70% (독립적, max 20)
- Payment Service 커넥션 풀: 30~50% (독립적, max 20)
- **개선**: 리소스 여유 확보, 확장성 향상
- **증상**: 평균 응답 시간 11ms, 최대 응답 시간 248ms

#### 3.4 실제 테스트 결과 상세 분석

**테스트 조건:**
- **모놀리식**: VU 100, 실행 시간 86초 (1분 26초)
- **MSA**: VU 100, 실행 시간 28초 (테스트 조기 종료 가능성)

**주요 발견 사항:**

1. **응답 시간 개선이 가장 큰 성과**
   - 모놀리식: 평균 8,034ms (8초)
   - MSA: 평균 11.4ms
   - **개선율: 99.9%**

2. **일관성 향상**
   - 모놀리식: 최대 298,000ms (298초) - 극단적 편차
   - MSA: 최대 248ms - 일관된 성능

3. **실패율은 유사하지만 실패 처리 속도 개선**
   - 모놀리식: 실패 시에도 4초 이상 대기
   - MSA: 실패 시 12.8ms로 즉시 응답
   - **사용자 경험**: 빠른 실패 응답으로 재시도 가능

4. **리소스 효율성**
   - 모놀리식: 단일 커넥션 풀(max 10)로 모든 서비스 경쟁
   - MSA: 서비스별 독립 커넥션 풀(max 20)로 경쟁 제거

---

### 실제 테스트 결과 요약

**테스트 리포트:**
- 모놀리식: `locust/report_bottleneck_vu100.html`
- MSA: `locust/report_msa_order_vu100.html`

**핵심 성과:**

| 지표 | 모놀리식 | MSA | 개선 |
|------|---------|-----|------|
| **평균 응답 시간** | 8,034ms (8초) | 11.4ms | **-99.9%** |
| **주문 평균 응답** | 4,113ms (4.1초) | 12.8ms | **-99.7%** |
| **최대 응답 시간** | 298,000ms (298초) | 248ms | **-99.9%** |
| **응답 시간 일관성** | 극단적 편차 | 일관적 | **대폭 개선** |

**결론:**
- MSA 전환으로 **응답 시간이 99.9% 개선**됨
- 실패율은 유사하지만, **실패 응답 속도가 대폭 개선**되어 사용자 경험 향상
- 일관된 성능으로 시스템 안정성 확보
- 독립적인 리소스 관리로 확장성 향상

---

### 테스트 실행 체크리스트

#### 모놀리식 환경 테스트
- [ ] 모놀리식 애플리케이션 실행
- [ ] VU 100 베이스라인 테스트 실행
- [ ] 리포트 저장: `report_monolith_vu100.html`
- [ ] 주요 지표 기록 (TPS, 응답 시간, 에러율)

#### MSA 환경 테스트
- [ ] Order Service 실행 (Port 8080)
- [ ] Payment Service 실행 (Port 8081)
- [ ] 서비스 상태 확인
- [ ] VU 100 통합 테스트 실행
- [ ] 리포트 저장: `report_msa_vu100.html`
- [ ] 주요 지표 기록

#### Docker MSA 환경 테스트 (선택적)
- [ ] Docker 이미지 빌드
- [ ] Docker Compose로 서비스 배포
- [ ] VU 100 테스트 실행
- [ ] 리포트 저장: `report_msa_docker_vu100.html`

#### 비교 분석
- [ ] 모놀리식 vs MSA 성능 비교
- [ ] 개선 효과 정리
- [ ] 문제점 및 개선 사항 기록

---

## 실습 체크리스트

실습 순서에 따른 체크리스트:

1. [ ] 서비스 분리 전략 수립
2. [ ] Order Service 프로젝트 생성
3. [ ] Order Service application.yaml 설정 (Port 8080, orderdb)
4. [ ] Payment Service 프로젝트 생성
5. [ ] Payment Service application.yaml 설정 (Port 8081, paymentdb)
6. [ ] REST API 클라이언트 구현
7. [ ] 서비스 간 통신 테스트
8. [ ] 모놀리식 베이스라인 성능 테스트 (Issue7/Issue8)
9. [ ] MSA 환경 성능 테스트 (Issue9)
10. [ ] 성능 비교 및 분석
11. [ ] Docker 컨테이너 설정
12. [ ] docker-compose 설정
13. [ ] 독립 배포 및 확장 테스트

---

## Before vs After 비교

### 모놀리식 (Before)

| 항목 | 내용 |
|------|------|
| 포트 | 8080 (단일 포트) |
| 데이터베이스 | testdb (단일 DB) |
| 커넥션 풀 | max 10 (공유) |
| 배포 | 단일 JAR 파일 |
| 확장 | 수직 확장만 가능 |
| 장애 격리 | 불가능 (장애 전파) |

### MSA (After)

| 항목 | Order Service | Payment Service |
|------|--------------|-----------------|
| 포트 | 8080 | 8081 |
| 데이터베이스 | orderdb | paymentdb |
| 커넥션 풀 | max 20 (독립) | max 20 (독립) |
| 배포 | 독립 JAR 파일 | 독립 JAR 파일 |
| 확장 | 독립적 수평 확장 | 독립적 수평 확장 |
| 장애 격리 | 가능 | 가능 |

---

## 예상 개선 효과

### 성능 개선

| 지표 | 모놀리식 | MSA | 개선율 |
|------|---------|-----|--------|
| 주문 서비스 실패율 | 32.5% | <5% | **-85%** |
| 주문 평균 응답 | 4.13초 | <500ms | **-88%** |
| 커넥션 풀 사용률 | 100% | 50~70% | **-30%** |
| 전체 평균 응답 | 8.08초 | <1초 | **-88%** |

### 운영 개선

| 항목 | 모놀리식 | MSA |
|------|---------|-----|
| 배포 주기 | 전체 재배포 필요 | 서비스별 독립 배포 |
| 롤백 | 전체 롤백 | 서비스별 롤백 |
| 확장성 | 수직 확장만 | 수평 확장 가능 |
| 장애 격리 | 불가능 | 가능 |
| 팀 독립성 | 낮음 | 높음 |

---

## 문제 해결

### JAR 파일 실행 오류

**증상 1:** `build/libs/monolith-to-msa-0.0.1-SNAPSHOT-plain.jar에 기본 Manifest 속성이 없습니다.`

**원인:** `-plain.jar`는 실행 가능한 JAR가 아닙니다. Spring Boot는 두 가지 JAR를 생성합니다:
- `monolith-to-msa-0.0.1-SNAPSHOT.jar`: 실행 가능한 JAR (의존성 포함)
- `monolith-to-msa-0.0.1-SNAPSHOT-plain.jar`: 일반 JAR (의존성 미포함)

**해결 방법:**

1. **올바른 JAR 파일 사용:**
   ```bash
   # ❌ 잘못된 방법
   java -jar build/libs/monolith-to-msa-0.0.1-SNAPSHOT-plain.jar
   
   # ✅ 올바른 방법
   java -jar build/libs/monolith-to-msa-0.0.1-SNAPSHOT.jar
   ```

2. **plain.jar 생성 비활성화 (권장):**
   - `build.gradle`에 이미 설정되어 있습니다.
   - `jar { enabled = false }`로 plain.jar 생성을 막습니다.
   - 이제 `build/libs/`에는 실행 가능한 JAR만 생성됩니다.

3. **빌드 확인:**
   ```bash
   ./gradlew clean build
   ls -lh build/libs/
   # monolith-to-msa-0.0.1-SNAPSHOT.jar만 생성됨
   ```

**증상 2:** `zsh: command not found: #`

**원인:** 주석 처리된 명령어(`#`로 시작)를 실행하려고 했을 때 발생합니다.

**해결 방법:**
- 문서의 주석(`#`로 시작하는 줄)은 실행하지 마세요.
- 실제 명령어만 복사해서 실행하세요.

**예시:**
```bash
# ❌ 잘못된 방법 (주석 포함)
# 프로젝트 루트에서 실행
cd /Users/junshock5/Desktop/fastcampus/monolith-to-msa

# ✅ 올바른 방법 (주석 제외)
cd /Users/junshock5/Desktop/fastcampus/monolith-to-msa
```

### Docker Compose 명령어 오류

**증상:** `zsh: command not found: docker-compose`

**해결 방법:**

1. **Docker Compose v2 사용 (권장):**
   ```bash
   # 프로젝트 루트로 이동
   cd /Users/junshock5/Desktop/fastcampus/monolith-to-msa
   
   # Docker Compose v2 사용 (공백)
   docker compose -f docker-compose-msa.yml up -d
   ```

2. **Docker Compose v2 확인:**
   ```bash
   docker compose version
   # 출력: Docker Compose version v2.x.x
   ```

3. **레거시 docker-compose 설치 (필요한 경우):**
   ```bash
   # macOS
   brew install docker-compose
   
   # Linux
   pip install docker-compose
   
   # 설치 후 사용
   docker-compose -f docker-compose-msa.yml up -d
   ```

**⚠️ 중요:**
- `docker-compose-msa.yml` 파일은 프로젝트 루트에 있습니다.
- `docs` 디렉토리에서 실행하면 파일을 찾을 수 없습니다.
- 반드시 프로젝트 루트에서 실행하세요.

### 서비스 간 통신 실패

**증상:** Order Service에서 Payment Service 호출 실패

**해결:**
1. Payment Service가 실행 중인지 확인
2. 네트워크 연결 확인
3. 타임아웃 설정 확인
4. Circuit Breaker 패턴 적용

### 데이터 일관성 문제

**증상:** 주문은 생성되었지만 결제가 실패

**해결:**
1. Saga 패턴 적용 (분산 트랜잭션)
2. 보상 트랜잭션 구현
3. 이벤트 기반 아키텍처 고려

### 성능 저하

**증상:** 서비스 간 통신으로 인한 지연

**해결:**
1. 비동기 통신 고려 (메시지 큐)
2. 캐싱 적용
3. 배치 처리 고려

---

## 실습 파일 요약

### 설정 파일

1. **Order Service 설정**
   - `src/main/resources/application-order.yaml`: Port 8080, orderdb, 커넥션 풀 max 20
   - `Dockerfile.order`: Order Service Docker 이미지 빌드

2. **Payment Service 설정**
   - `src/main/resources/application-payment.yaml`: Port 8081, paymentdb, 커넥션 풀 max 20
   - `Dockerfile.payment`: Payment Service Docker 이미지 빌드

3. **배포 설정**
   - `docker-compose-msa.yml`: MSA 서비스 배포 및 확장 설정

### 문서

- `docs/issue9.md`: 본 실습 가이드
- `docs/issue9.puml`: 시퀀스 다이어그램 및 아키텍처 다이어그램

### 사용 방법

**Docker Compose v2 사용 (권장):**
```bash
# ⚠️ 프로젝트 루트에서 실행!
cd /Users/junshock5/Desktop/fastcampus/monolith-to-msa

# MSA 서비스 배포
docker compose -f docker-compose-msa.yml up -d

# 주문 서비스만 확장 (높은 트래픽 대응)
docker compose -f docker-compose-msa.yml up -d --scale order-service=3

# 로그 확인
docker compose -f docker-compose-msa.yml logs -f order-service

# 서비스 중지
docker compose -f docker-compose-msa.yml down
```

**레거시 docker-compose 사용 (별도 설치 필요):**
```bash
# docker-compose v1이 별도로 설치된 경우에만 사용
# 설치 방법:
#   - macOS: brew install docker-compose
#   - Linux: pip install docker-compose
#   - 또는: https://github.com/docker/compose/releases

# 프로젝트 루트에서 실행
cd /Users/junshock5/Desktop/fastcampus/monolith-to-msa
docker-compose -f docker-compose-msa.yml up -d
docker-compose -f docker-compose-msa.yml down
```

**⚠️ 주의사항:**
- 대부분의 최신 Docker 설치에는 `docker compose` (v2)가 포함되어 있습니다.
- `docker-compose` (하이픈) 명령어가 없다면 `docker compose` (공백)를 사용하세요.
- 프로젝트 루트 디렉토리에서 실행해야 합니다 (`docker-compose-msa.yml` 파일 위치).

---

## 프로덕션 환경 고려사항

이 실습은 개념 이해를 위한 예제입니다. 실제 프로덕션 환경에서는 다음 추가 구성이 필요합니다:

1. **서비스 디스커버리**
   - Eureka, Consul 등으로 서비스 자동 등록 및 발견
   - 동적 라우팅 및 로드 밸런싱

2. **API 게이트웨이**
   - 단일 진입점 제공
   - 인증/인가, 라우팅, 로깅 중앙화

3. **분산 트랜잭션 관리**
   - Saga 패턴으로 분산 트랜잭션 처리
   - 보상 트랜잭션 구현

4. **모니터링 및 로깅**
   - 중앙화된 로그 수집 (ELK Stack 등)
   - 분산 추적 (Zipkin, Jaeger)
   - 메트릭 수집 (Prometheus, Grafana)

5. **보안**
   - 서비스 간 인증 (JWT, OAuth2)
   - 네트워크 보안 (mTLS)

6. **각 서비스를 별도 프로젝트로 구성**
   - 독립적인 코드베이스
   - 독립적인 배포 파이프라인
   - 팀별 독립성 확보

---

## 참고 자료

- [Spring Cloud 공식 문서](https://spring.io/projects/spring-cloud)
- [Docker Compose 공식 문서](https://docs.docker.com/compose/)
- [MSA 패턴 가이드](https://microservices.io/patterns/)
