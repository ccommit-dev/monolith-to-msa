# Ch06.09 서비스 분리 설계 실습 가이드

## 실습 목표

- 단일 루트 프로젝트를 Order 역할과 Payment 역할로 나누어 실행한다.
- `application-order.yaml`, `application-payment.yaml`로 서비스별 포트와 DB를 분리한다.
- `Dockerfile.order`, `Dockerfile.payment`로 같은 소스를 각각 다른 서비스 이미지로 빌드한다.
- `docker-compose-msa.yml`로 Order 컨테이너와 Payment 컨테이너를 함께 실행한다.

---

## 0) 이번 강의의 분리 방식

이번 실습에서 말하는 서비스 분리는 저장소나 Gradle 프로젝트를 물리적으로 둘로 나누는 방식이 아니다.

강의 영상 기준 구조는 아래와 같다.

```text
monolith-to-msa/
├── src/main/java/com/ccommit/monolith_to_msa/...
├── src/main/resources/application-order.yaml
├── src/main/resources/application-payment.yaml
├── Dockerfile.order
├── Dockerfile.payment
└── docker-compose-msa.yml
```

즉, 하나의 루트 프로젝트 소스를 빌드하되 실행 프로필을 다르게 주어 다음처럼 동작하게 만든다.

| 컨테이너 | 빌드 파일 | 실행 프로필 | 포트 | 역할 |
|---|---|---|---|---|
| `order-service` | `Dockerfile.order` | `order` | `8080` | 주문 API, Payment 서비스 호출 |
| `payment-service` | `Dockerfile.payment` | `payment` | `8081` | 결제 API |

따라서 영상에서 루트 프로젝트의 `OrderServiceImpl`, `PaymentClient`, 설정 파일을 수정하면 Docker Compose로 실행되는 `order-service` 이미지에도 그 변경이 반영된다.

---

## 1) Order Service 실행 설정

### 1-1. `application-order.yaml`

`src/main/resources/application-order.yaml`은 Order 서비스 역할로 실행될 때 사용하는 설정이다.

핵심 확인 포인트:

- `server.port: 8080`
- `spring.application.name: order-service`
- `jdbc:h2:mem:orderdb`
- `payment.service.url: ${PAYMENT_SERVICE_URL:http://localhost:8081}`

Order 서비스는 주문 생성 후 Payment 서비스의 HTTP API를 호출하므로 `PAYMENT_SERVICE_URL`을 환경 변수로 받을 수 있어야 한다.

### 1-2. `Dockerfile.order`

`Dockerfile.order`는 루트 프로젝트를 빌드하고, 실행 시 `SPRING_PROFILES_ACTIVE=order`를 지정한다.

```dockerfile
ENV SPRING_PROFILES_ACTIVE=order
ENV SPRING_DATASOURCE_URL=jdbc:h2:mem:orderdb
ENV PAYMENT_SERVICE_URL=http://payment-service:8081
```

이 설정 때문에 루트의 `src/main/java/...` 코드가 Order 서비스 컨테이너에 포함된다.

---

## 2) Payment Service 실행 설정

### 2-1. `application-payment.yaml`

`src/main/resources/application-payment.yaml`은 Payment 서비스 역할로 실행될 때 사용하는 설정이다.

핵심 확인 포인트:

- `server.port: 8081`
- `spring.application.name: payment-service`
- `jdbc:h2:mem:paymentdb`

Payment 서비스는 결제 API를 제공하고, Order 서비스와 별도 H2 DB를 사용한다.

### 2-2. `Dockerfile.payment`

`Dockerfile.payment`도 루트 프로젝트를 빌드하지만, 실행 시 `SPRING_PROFILES_ACTIVE=payment`를 지정한다.

```dockerfile
ENV SPRING_PROFILES_ACTIVE=payment
ENV SPRING_DATASOURCE_URL=jdbc:h2:mem:paymentdb
```

---

## 3) Docker Compose 구성

`docker-compose-msa.yml`은 같은 루트 프로젝트를 두 개의 이미지로 빌드한다.

```yaml
services:
  order-service:
    build:
      context: .
      dockerfile: Dockerfile.order
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=order
      - PAYMENT_SERVICE_URL=http://payment-service:8081

  payment-service:
    build:
      context: .
      dockerfile: Dockerfile.payment
    ports:
      - "8081:8081"
    environment:
      - SPRING_PROFILES_ACTIVE=payment
```

Compose 네트워크 안에서는 `payment-service`라는 서비스 이름으로 Payment 컨테이너에 접근할 수 있다. 그래서 Order 컨테이너의 `PAYMENT_SERVICE_URL`은 `http://payment-service:8081`로 둔다.

---

## 4) 실행

```bash
docker compose -f docker-compose-msa.yml up -d --build
```

상태 확인:

```bash
docker compose -f docker-compose-msa.yml ps
docker compose -f docker-compose-msa.yml logs -f order-service
docker compose -f docker-compose-msa.yml logs -f payment-service
```

중지:

```bash
docker compose -f docker-compose-msa.yml down
```

---

## 5) API 확인

Payment 서비스 직접 호출:

```bash
curl -X POST http://localhost:8081/api/payments \
  -H "Content-Type: application/json" \
  -d '{"orderId":1,"amount":20000,"method":"CREDIT_CARD"}'
```

Order 서비스 주문 생성:

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId":"customer1","productId":"product1","quantity":2,"totalPrice":20000}'
```

Order 서비스는 주문을 저장한 뒤 `PAYMENT_SERVICE_URL`을 통해 Payment 서비스로 결제 요청을 보낸다.

---

## 6) 영상 흐름과 코드 경로 정리

영상에서 수정하는 코드는 별도 `order-service/` 디렉터리가 아니라 루트 프로젝트의 코드다.

| 영상에서 다루는 역할 | 실제 코드 위치 |
|---|---|
| 주문 서비스 로직 | `src/main/java/com/ccommit/monolith_to_msa/service/order/OrderServiceImpl.java` |
| 결제 서비스 로직 | `src/main/java/com/ccommit/monolith_to_msa/service/payment/PaymentServiceImpl.java` |
| Payment 호출 클라이언트 | `src/main/java/com/ccommit/monolith_to_msa/client/PaymentClient.java` |
| Order 실행 설정 | `src/main/resources/application-order.yaml` |
| Payment 실행 설정 | `src/main/resources/application-payment.yaml` |
| Order 이미지 | `Dockerfile.order` |
| Payment 이미지 | `Dockerfile.payment` |

이 구조를 이해하면, 루트 프로젝트의 코드를 수정한 뒤 Docker Compose로 `order-service`, `payment-service`를 빌드하고 테스트하는 이유가 자연스럽게 연결된다.

---

## 다음 단계

Issue 10에서는 이 구조 위에서 Order 서비스가 Payment 서비스를 호출하는 통신 방식을 더 안정적으로 바꾼다.

- `RestTemplate` 또는 단순 HTTP 호출 구조 점검
- `PaymentClient` 책임 분리
- timeout, retry, circuit breaker, fallback 같은 장애 대응 추가
