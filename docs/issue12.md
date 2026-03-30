# Ch06.12: 성능 비교 — Before vs After

## 실습 목표

- 동일 Locust 시나리오로 **Before(단일 앱·병목 설정)** 와 **After(MSA·개선 설정)** 를 각각 측정한다.
- `compare_performance.py`로 **TPS·응답 시간·실패율**을 집계 비교한다.
- Ch06에서 도입한 **DB 분리, 캐시, 비동기, 회복 탄력성**이 측정값에 어떻게 반영되는지 해석한다.

---

## 실습 순서 한눈에 보기

아래 **순서 번호는 문서의 `N단계` 소제목과 동일**합니다.

| 순서 | 단계 | 하는 일 | 주요 산출물 |
|------|------|---------|-------------|
| 0 | 사전 준비 | Python venv, Locust, **Redis**, JDK 17+ | `locust/venv`, Redis `6379` |
| 1 | 부하 시나리오 | `load_test_performance_comparison.py` 구조 파악 | (코드 읽기) |
| 2 | 비교 스크립트 | `compare_performance.py` 역할·CSV 규칙 | (코드 읽기) |
| 3 | **Before** 앱 기동 | 기본 프로필 단일 프로세스 | `http://localhost:8080` |
| 4 | **Before** 부하 | Locust → Order API만(8080) | `results_before_vu*_stats.csv`, HTML |
| 5 | **After** 앱 기동 | Order(8080) + Payment(8081) + Redis | MSA와 동일 |
| 6 | **After** 부하 | Before와 동일 조건 Locust | `results_after_vu*_stats.csv`, HTML |
| 7 | 결과 비교 | `compare_performance.py` 실행 | `performance_comparison.json` |

### 작업 디렉터리 (실행할 폴더)

| 명령 종류 | 디렉터리 |
|-----------|----------|
| `gradlew` / `gradlew.bat` | **저장소 루트** (`monolith-to-msa/`) |
| Locust(`-f load_test_...py`, `--csv=...`) | **`locust/`** (CSV·HTML이 여기에 생김) |
| `compare_performance.py` | **`locust/`** 권장 (또는 `_stats.csv` 절대 경로 인자) |

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
| 실행 | Linux/macOS: `./gradlew bootRun` / Windows: `.\gradlew.bat bootRun` (프로필 미지정 또는 `default`) |
| 포트 | 8080 단일 |
| DB | H2 `testdb` 단일 |
| 커넥션 풀 | Hikari **maximum-pool-size: 10** (병목 재현용) |
| 캐시 | `spring.cache.type: simple` (기본 yaml 기준) |
| 통신 | 한 JVM 내 Order·Payment 등 공존 가능하나, 현재 코드는 주문 생성 후 **Redis Pub/Sub**으로 이벤트 발행 |

**주의:** 이 프로젝트는 `spring-boot-starter-data-redis`를 포함합니다. `application.yaml`에 Redis 호스트가 주석이어도 Boot 기본값으로 **localhost:6379**에 붙으려 할 수 있어, **Before(기본 프로필) 포함 Redis(6379)를 켜 두는 것**을 강력히 권장합니다. Redis가 없으면 기동 실패 또는 Pub/Sub·이벤트 처리 오류가 날 수 있습니다.

### After: `order` + `payment` 프로필

| 항목 | 내용 |
|------|------|
| 실행 | 터미널1·2: 각각 `order` / `payment` 프로필로 `bootRun` (Linux/macOS는 `--args='...'`, Windows PowerShell은 `.\gradlew.bat bootRun --args="--spring.profiles.active=order"` 등) |
| DB | `orderdb` / `paymentdb` 분리 (각 프로필 yaml) |
| 커넥션 풀 | 서비스별 **max 20** 등 독립 설정 |
| 캐시 | Order 프로필에서 **Redis 캐시** (`application-order.yaml`) |
| 통신 | Redis Pub/Sub, Payment **WebClient**, Resilience4j |

Locust는 **항상 Order의 베이스 URL**만 넣습니다 (`--host=http://localhost:8080`). Payment는 Order가 내부적으로 호출합니다.

---

## 0단계: 사전 준비

1. **Redis**  
   - Linux / macOS / Windows 공통 (Docker):

     ```bash
     docker run -d -p 6379:6379 redis:latest
     ```

   - Windows에서 Docker 없이: [Redis for Windows](https://redis.io/docs/install/install-redis/install-redis-on-windows/) 또는 WSL2 안에서 `redis-server` 등 로컬 설치 후 6379 리슨.

   **연결 확인 (선택)**

   ```bash
   redis-cli -h 127.0.0.1 -p 6379 ping
   ```

   **Windows PowerShell**

   ```powershell
   Test-NetConnection -ComputerName 127.0.0.1 -Port 6379
   ```

   `TcpTestSucceeded : True` 이면 포트가 열려 있는 상태입니다.

2. **JDK** — Gradle이 요구하는 버전(예: **Java 17+**)이 `JAVA_HOME`·PATH에 잡혀 있는지 확인합니다. `java -version`

3. **Python / Locust** (`locust` 디렉터리에서)

   **Linux / macOS**

   ```bash
   cd locust
   python3 -m venv venv
   source venv/bin/activate
   pip install -r requirements.txt
   ```

   **Windows — PowerShell** (저장소 루트에서 `locust`로 이동)

   ```powershell
   cd locust
   python -m venv venv
   .\venv\Scripts\Activate.ps1
   pip install -r requirements.txt
   ```

   `Activate.ps1` 실행이 막히면(ExecutionPolicy): 현재 세션만 허용하려면 `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass` 후 다시 활성화하거나, 활성화 없이 아래처럼 **venv의 Python만** 써도 됩니다.

   ```powershell
   cd locust
   .\venv\Scripts\pip.exe install -r requirements.txt
   ```

   > **`locust` 명령을 못 찾는 경우:** venv에 패키지가 없거나 `Scripts`가 PATH에 없을 수 있습니다. 4·6단계에서는 `python -m locust` 또는 `.\venv\Scripts\python.exe -m locust`를 사용하세요.

4. **프로젝트 빌드** (선택)

   **Linux / macOS**

   ```bash
   ./gradlew build -x test
   ```

   **Windows — PowerShell** (저장소 루트)

   ```powershell
   .\gradlew.bat build -x test
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
- Locust `*_stats.csv`에는 **`Failure Rate` 컬럼이 없는 경우가 많습니다.** 스크립트는 `Failure Count` / `Request Count`로 실패율(%)을 계산합니다.
- 결과를 `performance_comparison.json`으로 저장합니다.

### Locust CSV 파일 이름 (필수 이해)

`locust -f ... --csv=results_before_vu100` 또는 `python -m locust -f ... --csv=results_before_vu100` 실행 시 **현재 작업 디렉터리**에 다음과 같이 생성됩니다.

- `results_before_vu100_stats.csv` ← 집계에 사용
- `results_before_vu100_failures.csv` 등

즉 **디렉터리 `results_before_vu100/`가 생기지 않습니다.**  
비교 스크립트에는 **접두사** `results_before_vu100` 또는 **`_stats.csv` 전체 경로**를 넘깁니다.

### 사용법

> **전제 조건:** 아래 명령은 **이미 Locust가 남긴 통계 CSV가 있을 때만** 성공합니다.  
> `locust/`(또는 현재 cwd)에 **`results_before_vu100_stats.csv`**, **`results_after_vu100_stats.csv`** 가 없으면 오류가 나는 것이 정상입니다.  
> 먼저 **4단계·6단계**에서 `--csv=results_before_vu100` / `--csv=results_after_vu100` 로 부하 테스트를 실행해 두세요.

**Linux / macOS**

```bash
cd locust
python compare_performance.py results_before_vu100 results_after_vu100
# 선택: 출력 파일명
python compare_performance.py results_before_vu100 results_after_vu100 my_report.json
```

**Windows — PowerShell**

```powershell
cd locust
python compare_performance.py results_before_vu100 results_after_vu100
# 선택: 출력 JSON 파일명
python compare_performance.py results_before_vu100 results_after_vu100 my_report.json
```

활성화 없이 실행할 때:

```powershell
cd locust
.\venv\Scripts\python.exe compare_performance.py results_before_vu100 results_after_vu100
```

### 실습 포인트

- **`compare_performance.py`는 결과를 만들지 않습니다.** 입력으로 쓰는 `*_stats.csv`는 **Locust가 생성**합니다.
- 반드시 **`locust` 폴더**에서 실행하거나, 인자로 `_stats.csv`의 절대 경로를 넘깁니다.
- Before/After **VU 수·실행 시간**을 맞추면 비교가 공정해집니다.

---

## 3단계: Before 환경 기동

1. 이전에 띄운 Order/MSA 프로세스가 있으면 **종료**합니다.
2. Redis가 실행 중인지 확인합니다.
3. 저장소 루트에서:

   **Linux / macOS**

   ```bash
   ./gradlew bootRun
   ```

   **Windows — PowerShell**

   ```powershell
   .\gradlew.bat bootRun
   ```

4. 헬스 확인: `http://localhost:8080/actuator/health` (설정에 따라 다름)

---

## 4단계: Before 부하 테스트 (Locust)

`locust` 디렉터리에서 실행합니다.

**Linux / macOS** (가상환경 활성화 후)

```bash
cd locust
source venv/bin/activate
locust -f load_test_performance_comparison.py \
    --host=http://localhost:8080 \
    --headless \
    --users=100 \
    --spawn-rate=10 \
    --run-time=5m \
    --html=report_before_vu100.html \
    --csv=results_before_vu100
```

`locust` 대신 모듈 실행:

```bash
python -m locust -f load_test_performance_comparison.py \
    --host=http://localhost:8080 --headless --users=100 --spawn-rate=10 --run-time=5m \
    --html=report_before_vu100.html --csv=results_before_vu100
```

**Windows — PowerShell** (`locust`가 인식되지 않으면 **아래 둘 중 하나** 권장)

```powershell
cd locust
.\venv\Scripts\Activate.ps1
python -m locust -f load_test_performance_comparison.py --host=http://localhost:8080 --headless --users=100 --spawn-rate=10 --run-time=5m --html=report_before_vu100.html --csv=results_before_vu100
```

활성화 없이(venv의 Python 직접 사용):

```powershell
cd locust
& ".\venv\Scripts\python.exe" -m locust -f load_test_performance_comparison.py --host=http://localhost:8080 --headless --users=100 --spawn-rate=10 --run-time=5m --html=report_before_vu100.html --csv=results_before_vu100
```

`pip install -r requirements.txt`까지 끝난 뒤에는 `locust.exe`가 생기므로, 활성화된 상태에서 아래도 가능합니다.

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

   **Linux / macOS**

   ```bash
   ./gradlew bootRun --args='--spring.profiles.active=order'
   ```

   **Windows — PowerShell** (저장소 루트)

   ```powershell
   .\gradlew.bat bootRun --args="--spring.profiles.active=order"
   ```

4. **터미널 B — Payment**

   **Linux / macOS**

   ```bash
   ./gradlew bootRun --args='--spring.profiles.active=payment'
   ```

   **Windows — PowerShell** (저장소 루트)

   ```powershell
   .\gradlew.bat bootRun --args="--spring.profiles.active=payment"
   ```

5. Order `8080`, Payment `8081` 리슨 확인.

   - 브라우저: `http://localhost:8080/actuator/health`, `http://localhost:8081/actuator/health`
   - **Windows PowerShell:** `Get-NetTCPConnection -LocalPort 8080,8081 -State Listen`

---

## 6단계: After 부하 테스트 (Locust)

Before와 **동일한** `--users`, `--spawn-rate`, `--run-time` 권장.

**Linux / macOS**

```bash
cd locust
source venv/bin/activate
locust -f load_test_performance_comparison.py \
    --host=http://localhost:8080 \
    --headless \
    --users=100 \
    --spawn-rate=10 \
    --run-time=5m \
    --html=report_after_vu100.html \
    --csv=results_after_vu100
```

또는:

```bash
python -m locust -f load_test_performance_comparison.py \
    --host=http://localhost:8080 --headless --users=100 --spawn-rate=10 --run-time=5m \
    --html=report_after_vu100.html --csv=results_after_vu100
```

**Windows — PowerShell**

```powershell
cd locust
python -m locust -f load_test_performance_comparison.py --host=http://localhost:8080 --headless --users=100 --spawn-rate=10 --run-time=5m --html=report_after_vu100.html --csv=results_after_vu100
```

활성화 없이:

```powershell
cd locust
& ".\venv\Scripts\python.exe" -m locust -f load_test_performance_comparison.py --host=http://localhost:8080 --headless --users=100 --spawn-rate=10 --run-time=5m --html=report_after_vu100.html --csv=results_after_vu100
```

**확인:** `results_after_vu100_stats.csv`

---

## 7단계: 성능 비교 실행

**선행:** `locust/`에 **`results_before_vu100_stats.csv`**, **`results_after_vu100_stats.csv`** 가 이미 있어야 합니다 (없으면 4·6단계 Locust를 먼저 실행).

**Linux / macOS**

```bash
cd locust
source venv/bin/activate
python compare_performance.py results_before_vu100 results_after_vu100
```

**Windows — PowerShell**

```powershell
cd locust
python compare_performance.py results_before_vu100 results_after_vu100
# 활성화 없이:
# .\venv\Scripts\python.exe compare_performance.py results_before_vu100 results_after_vu100
```

콘솔에 Before/After TPS·평균 응답·개선율이 출력되고, `performance_comparison.json`이 **현재 작업 디렉터리**에 생성됩니다.

---

## 트러블슈팅

| 증상 | 점검 |
|------|------|
| `'locust' 용어가 ... 인식되지 않습니다` (Windows) | `pip install -r requirements.txt` 후 `python -m locust ...` 또는 `.\venv\Scripts\python.exe -m locust ...` 사용 |
| `Connection refused` / Locust 전부 실패 | 저장소 루트에서 **3단계** `bootRun` 여부, `http://localhost:8080/actuator/health` |
| After에서 주문·결제 오류 | **5단계** — Order(8080)·Payment(8081) **둘 다** 기동, Redis 유지 |
| 포트 사용 중 (8080 / 8081) | 다른 `bootRun`·프로세스 종료. Windows: `Get-NetTCPConnection -LocalPort 8080` 후 PID 종료 |
| `통계 파일을 찾을 수 없습니다` | Locust는 **`locust/`** 에서 돌렸는지, 접두사와 `*_stats.csv` 파일명이 일치하는지 |
| `performance_comparison.json`이 안 보임 | **7단계 실행 시 cwd**에 생성됨. `locust`에서 실행했으면 `locust/performance_comparison.json` 확인 |
| 한글 로그가 깨짐 | 터미널 UTF-8 설정 또는 `compare_performance.py`가 stdout UTF-8로 재설정함(Windows) |

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
