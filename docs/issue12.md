# Ch06.12: 성능 비교 — Before vs After

## 실습 목표

- 동일 Locust 시나리오로 **Before(단일 앱·병목 설정)** 와 **After(MSA·개선 설정)** 를 각각 측정한다.
- `compare_performance.py`로 **TPS·응답 시간·실패율**을 집계 비교한다.
- Ch06에서 도입한 **DB 분리, 캐시, 비동기, 회복 탄력성**이 측정값에 어떻게 반영되는지 해석한다.

---

## 실습 순서 한눈에 보기

| 순서 | 단계 | 하는 일 | 주요 산출물 |
|------|------|---------|-------------|
| 0 | 사전 준비 | Python 가상환경, Locust, **Redis**, JDK | `locust/venv`, Redis 6379 |
| 1 | 스크립트 이해 | 부하 시나리오·비교 스크립트 읽기 | `load_test_performance_comparison.py`, `compare_performance.py` |
| 2 | **Before** 앱 기동 | 기본 프로필(모놀리식) 단일 프로세스 | `http://localhost:8080` |
| 3 | **Before** 부하 | Locust → Order API만 타깃(8080) | `results_before_vu*_stats.csv`, HTML |
| 4 | **After** 앱 기동 | Order(8080) + Payment(8081) + Redis | MSA 실습과 동일 |
| 5 | **After** 부하 | 동일 Locust 명령 | `results_after_vu*_stats.csv`, HTML |
| 6 | 결과 비교 | Python 비교 스크립트 | `performance_comparison.json` |
| 7 | (선택) 자동화 | 셸 스크립트 / PowerShell 안내 | `run_performance_comparison.sh`, `run_performance_comparison.ps1` |

이후 절은 위 순서에 맞춰 **환경 정의 → 파일별 설명 → 명령어** 순으로 적습니다.

---

## 수치 표에 대해 (중요)

문서 아래에 있는 **TPS ~95 → ~380**, **평균 응답 ~8s → ~11ms** 등은 **교육용으로 정리한 참고 예시**입니다. 실제 값은 CPU·메모리·VU 수·실행 시간·재고 소진·네트워크에 따라 크게 달라집니다.

**실습의 목적**은 숫자의 절대값이 아니라, 같은 시나리오에서 **Before 대비 After의 상대적 변화(처리량·지연·실패 패턴)** 를 확인하는 것입니다.

---

## Before vs After 환경 정의 (이 장 기준)

### Before: 기본 프로필 (`application.yaml`)

| 항목 | 내용 |
|------|------|
| 실행 | `./gradlew bootRun` (프로필 미지정 또는 `default`) |
| 포트 | 8080 단일 |
| DB | H2 `testdb` 단일 |
| 커넥션 풀 | Hikari **maximum-pool-size: 10** (병목 재현용) |
| 캐시 | `spring.cache.type: simple` (기본 yaml 기준) |
| 통신 | 한 JVM 내 Order·Payment 등 공존 가능하나, 현재 코드는 주문 생성 후 **Redis Pub/Sub**으로 이벤트 발행 |

**주의:** Pub/Sub·캐시(redis)를 쓰는 빈이 있으면 **Redis가 떠 있어야** 기동·부하가 안정적입니다. Before 측정 전에도 **Redis를 켜 두는 것**을 권장합니다.

### After: `order` + `payment` 프로필

| 항목 | 내용 |
|------|------|
| 실행 | 터미널1: `bootRun --args='--spring.profiles.active=order'` (8080), 터미널2: `payment` (8081) |
| DB | `orderdb` / `paymentdb` 분리 (각 프로필 yaml) |
| 커넥션 풀 | 서비스별 **max 20** 등 독립 설정 |
| 캐시 | Order 프로필에서 **Redis 캐시** (`application-order.yaml`) |
| 통신 | Redis Pub/Sub, Payment **WebClient**, Resilience4j |

Locust는 **항상 Order의 베이스 URL**만 넣습니다 (`--host=http://localhost:8080`). Payment는 Order가 내부적으로 호출합니다.

---

## 0단계: 사전 준비

1. **Redis**  
   `docker run -d -p 6379:6379 redis:latest` 또는 로컬 `redis-server`

2. **Python / Locust** (`locust` 디렉터리에서)

   ```bash
   cd locust
   python -m venv venv
   # Windows PowerShell:
   .\venv\Scripts\Activate.ps1
   # Linux/macOS:
   source venv/bin/activate
   pip install -r requirements.txt
   ```

3. **프로젝트 빌드** (선택)

   ```bash
   ./gradlew.bat build -x test
   ```

---

## 1단계: 부하 시나리오 파일 — `locust/load_test_performance_comparison.py`

### 역할

- **Ch06.12 전용** Locust 사용자 클래스 `PerformanceComparisonUser` 정의.
- **상품·재고 API**와 **주문 생성 API**를 섞어서 호출해, 캐시·비동기 주문 응답·동시성을 한 번에 스트레스합니다.

### 구성 요약

| 블록 | 내용 |
|------|------|
| `ProductTaskSet` | `GET /api/products/{id}`, `GET /api/products/{id}/stock` (가중치 3:2) — `data.sql`의 `product-001` ~ `003` 과 일치 |
| `OrderTaskSet` | `POST /api/orders` — `paymentMethod: CREDIT_CARD`, 재고 부족 400은 시나리오상 성공 처리 |
| `PerformanceComparisonUser` | `wait_time = between(0.5, 1.5)`, `tasks = { ProductTaskSet: 3, OrderTaskSet: 5 }` |

### 실습 포인트

- Locust의 `--host`는 **스킴 포함** (`http://localhost:8080`).
- 상품 ID를 바꾸면 `data.sql`과 맞춰야 404가 줄어듭니다.

---

## 2단계: 비교 스크립트 — `locust/compare_performance.py`

### 역할

- Locust가 남긴 **통계 CSV**에서 `Name == Aggregated` 행만 읽어 Before/After의 **TPS(`Requests/s`)**, **평균 응답**, **p95/p99**, **실패율**을 비교합니다.
- 결과를 `performance_comparison.json`으로 저장합니다.

### Locust CSV 파일 이름 (필수 이해)

`locust -f ... --csv=results_before_vu100` 실행 시 **현재 작업 디렉터리**에 다음과 같이 생성됩니다.

- `results_before_vu100_stats.csv` ← 집계에 사용
- `results_before_vu100_failures.csv` 등

즉 **디렉터리 `results_before_vu100/`가 생기지 않습니다.**  
비교 스크립트에는 **접두사** `results_before_vu100` 또는 **`_stats.csv` 전체 경로**를 넘깁니다.

### 사용법

```bash
cd locust
python compare_performance.py results_before_vu100 results_after_vu100
# 선택: 출력 파일명
python compare_performance.py results_before_vu100 results_after_vu100 my_report.json
```

### 실습 포인트

- 반드시 **`locust` 폴더**에서 실행하거나, 인자로 `_stats.csv`의 절대 경로를 넘깁니다.
- Before/After **VU 수·실행 시간**을 맞추면 비교가 공정해집니다.

---

## 3단계: Before 환경 기동

1. 이전에 띄운 Order/MSA 프로세스가 있으면 **종료**합니다.
2. Redis가 실행 중인지 확인합니다.
3. 저장소 루트에서:

   ```bash
   ./gradlew bootRun
   ```

   Windows: `.\gradlew.bat bootRun`

4. 헬스 확인: `http://localhost:8080/actuator/health` (설정에 따라 다름)

---

## 4단계: Before 부하 테스트 (Locust)

`locust` 디렉터리에서 (가상환경 활성화 후):

```bash
cd locust
locust -f load_test_performance_comparison.py \
    --host=http://localhost:8080 \
    --headless \
    --users=100 \
    --spawn-rate=10 \
    --run-time=5m \
    --html=report_before_vu100.html \
    --csv=results_before_vu100
```

**Windows PowerShell (한 줄):**

```powershell
cd locust
locust -f load_test_performance_comparison.py --host=http://localhost:8080 --headless --users=100 --spawn-rate=10 --run-time=5m --html=report_before_vu100.html --csv=results_before_vu100
```

**확인:** `results_before_vu100_stats.csv` 생성 여부

---

## 5단계: After 환경 기동

1. **Before**용 `bootRun` 프로세스를 **중지**합니다.
2. Redis 유지.
3. **터미널 A — Order**

   ```bash
   ./gradlew bootRun --args='--spring.profiles.active=order'
   ```

4. **터미널 B — Payment**

   ```bash
   ./gradlew bootRun --args='--spring.profiles.active=payment'
   ```

5. Order `8080`, Payment `8081` 리슨 확인.

---

## 6단계: After 부하 테스트 (Locust)

Before와 **동일한** `--users`, `--spawn-rate`, `--run-time` 권장.

```bash
cd locust
locust -f load_test_performance_comparison.py \
    --host=http://localhost:8080 \
    --headless \
    --users=100 \
    --spawn-rate=10 \
    --run-time=5m \
    --html=report_after_vu100.html \
    --csv=results_after_vu100
```

**확인:** `results_after_vu100_stats.csv`

---

## 7단계: 성능 비교 실행

```bash
cd locust
python compare_performance.py results_before_vu100 results_after_vu100
```

콘솔에 Before/After TPS·평균 응답·개선율이 출력되고, `performance_comparison.json`이 생성됩니다.

---

## 8단계 (선택): 통합 스크립트

### Linux / macOS — `locust/run_performance_comparison.sh`

- venv 활성화, Locust 설치 확인 후 **대화형**으로 Before/After/비교를 묻습니다.
- 비교 단계는 **`${PREFIX}_stats.csv` 존재 여부**로 검사합니다 (디렉터리가 아님).

### Windows — `locust/run_performance_comparison.ps1`

- 실행 가능한 명령어를 **출력**합니다. (비대화형으로 직접 Locust를 돌리고 7단계에서 비교하는 흐름과 동일)

---

## 참고: Before vs After 지표 예시 (교육용)

아래는 **이전 베이스라인을 가정한 예시**이며, 실습에서 나온 CSV/JSON과 다를 수 있습니다.

### Before (단일 DB·작은 풀·단일 프로세스)

| 지표 | 예시 값 |
|------|---------|
| TPS | ~95 req/s |
| 평균 응답 | 수 초대 (병목·대기 재현 시) |
| 실패율 | 부하에 따라 증가 가능 |

### After (DB 분리·풀 확대·Redis·MSA)

| 지표 | 예시 경향 |
|------|-----------|
| TPS | Before 대비 **상승** (자원·설정에 따라 배수는 가변) |
| 평균 응답 | 주문 API 기준 **단축** (비동기·캐시·경합 완화) |
| 최대 응답 | 타임아웃·서킷으로 **꼬리 구간** 완화 가능 |

### 개선에 자주 쓰는 설명 축

1. **독립 DB** — 커넥션 경합 완화, 장애·부하 격리  
2. **비동기(Pub/Sub)** — HTTP 응답을 결제 완료까지 붙잡지 않음  
3. **캐시(Redis)** — 상품/재고 조회의 DB 왕복 감소  
4. **Circuit Breaker** — 다운스트림 실패 시 빠른 실패·자원 보호  

---

## Ch06 흐름 복습 (측정 → 분석 → 개선 → 검증)

| 단계 | 챕터 예시 | 내용 |
|------|-----------|------|
| 측정 | Ch06.07~08 | Locust, 병목 지표 |
| 분석 | Ch06.08 | 풀 고갈, 트랜잭션 지연 |
| 개선 | Ch06.09~11 | MSA 분리, 캐시, 비동기, 회복 탄력성 |
| 검증 | **Ch06.12** | Before/After 동일 시나리오 비교 |

### 핵심 메시지

- **Measure** — 베이스라인 없이 개선을 말하기 어렵다.  
- **Analyze** — 병목 지점을 수치·로그로 특정한다.  
- **Improve** — 아키텍처·캐시·비동기·타임아웃을 단계적으로 적용한다.  
- **Verify** — 같은 부하로 재측정해 효과를 본다.

---

## 다음 단계 (선택)

- 서비스 디스커버리(Eureka, Consul), API 게이트웨이  
- 분산 추적(Zipkin, Jaeger)  
- Kafka 등 **영속 메시징**  
- DB 인덱스·쿼리 튜닝, 읽기 복제  

---

## 참고 자료

- [Locust 문서](https://docs.locust.io/)
- [Redis Pub/Sub](https://redis.io/docs/manual/pubsub/)
- [Resilience4j](https://resilience4j.readme.io/)
- [MSA 패턴](https://microservices.io/patterns/)

---

## 체크리스트

- [ ] Redis 기동  
- [ ] `locust/venv` + `pip install -r requirements.txt`  
- [ ] Before: 단일 `bootRun` 후 Locust → `_stats.csv` 확보  
- [ ] After: `order` + `payment` 기동 후 동일 조건 Locust → `_stats.csv` 확보  
- [ ] `compare_performance.py`로 JSON·콘솔 비교  
- [ ] HTML 리포트로 엔드포인트별 분포 확인  
- [ ] Ch06 흐름(측정→분석→개선→검증) 정리  
