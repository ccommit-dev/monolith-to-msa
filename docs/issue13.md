# Ch07.01: AI 트래픽 데이터 수집

## 실습 목표

- HTTP 트래픽에서 **TPS·지연·에러율·리소스(추정)** 를 주기적으로 집계해 DB에 저장한다.
- **Z-score·임계값** 기반으로 저장된 집계 행에 이상치 플래그를 붙인다.
- **Prometheus / Grafana**로 앱 메트릭과 커스텀 트래픽 메트릭을 스크랩·시각화한다.

---

## 실습 순서 한눈에 보기

| 순서 | 단계 | 하는 일 | 산출/확인 |
|------|------|---------|-----------|
| 0 | 사전 준비 | JDK, Redis(권장), Docker(모니터링용) | 환경 OK |
| 1 | 앱 기동 | 단일 프로세스 `bootRun` | `8080`, `traffic_metrics` 테이블 |
| 2 | 트래픽 발생 | API 호출 또는 Locust | 인터셉터 → 1분마다 DB 적재 |
| 3 | 메트릭 API 확인 | `curl` 등으로 JSON/Prometheus 텍스트 | `/api/metrics/*` |
| 4 | Prometheus·Grafana | `docker compose` 기동 | `:9090`, `:3000` |
| 5 | 이상 탐지·부하 | 학습 대기 후 Locust 등 | `/api/metrics/anomalies`, Prometheus `traffic_anomaly_score` |

**작업 디렉터리**

| 작업 | 디렉터리 |
|------|----------|
| `gradlew` / `gradlew.bat` | 저장소 루트 |
| `docker compose -f docker-compose-monitoring.yml` | 저장소 루트 |
| Locust | `locust/` |

---

## 0단계: 사전 준비

1. **JDK 17+** — `java -version`
2. **Redis** — 이 프로젝트는 기본 프로필에서도 Redis 연결을 기대하는 구성이 있을 수 있으므로 **localhost:6379** 기동을 권장합니다 (`issue12.md` 0단계 참고).
3. **Docker** — 4단계(Prometheus/Grafana)만 필요합니다. 없으면 1~3단계와 REST 조회만으로도 수집·이상 탐지 흐름은 실습 가능합니다.
4. **(선택) Locust** — `locust/`에서 `pip install -r requirements.txt` 후 `python -m locust ...` 사용.

---

## 1단계: 애플리케이션 기동

### 1.1 명령어

**Linux / macOS** (저장소 루트)

```bash
./gradlew bootRun
```

**Windows — PowerShell** (저장소 루트)

```powershell
.\gradlew.bat bootRun
```

### 1.2 이 단계에서 일어나는 일 (코드 기준)

| 구성요소 | 파일 | 역할 |
|----------|------|------|
| 스케줄링 | `config/MetricsConfig.java` | `@EnableScheduling` — 분 단위 집계·이상 탐지 스케줄 활성화 |
| 인터셉터 등록 | `config/WebConfig.java` | `/api/**`에 `MetricInterceptor` 등록 (메트릭 API 경로는 인터셉터에서 **제외**하여 Prometheus 스크랩이 원본 트래픽을 오염시키지 않음) |
| DB 스키마 | `domain/metrics/TrafficMetric.java` | JPA가 H2에 `traffic_metrics` 테이블 생성 (`ddl-auto: create-drop` 등 기존 설정 따름) |

> **참고:** `src/main/resources/db/migration/V4__create_traffic_metrics_table.sql` 은 **Flyway 도입 시** 참고·적용용입니다. 현재 `build.gradle`에는 Flyway 플러그인이 없으며, 기본 실습은 **엔티티 기준 자동 DDL**로 동작합니다.

### 1.3 기동 확인

```text
http://localhost:8080/actuator/health
```

**주의:** 기본 설정은 H2 메모리 DB라 애플리케이션 재기동 시 데이터가 초기화됩니다. 실습 중 재기동 후에도 데이터 유지가 필요하면 `src/main/resources/application.yaml`의 H2 설정에서 파일 모드 주석을 해제하세요.

```yaml
spring:
  datasource:
    # url: jdbc:h2:file:./data/testdb  # ← 주석 해제 시 재기동해도 데이터 유지
```

---

## 2단계: 트래픽 발생 — 수집·저장 파이프라인 이해

### 2.1 흐름 (요청 한 건부터 DB까지)

1. **`MetricInterceptor`** (`interceptor/MetricInterceptor.java`)  
   - `preHandle`: 요청 시각 저장  
   - `afterCompletion`: `/api/` 요청만 처리, **응답 시간(ms)**·**성공 여부(HTTP 200~399)** 기록  
   - `MetricCollectorService.recordRequest(endpoint, responseTime, success)` 호출 → 엔드포인트별 **메모리 버퍼**(`MetricWindow`)에 누적  

2. **`MetricCollectorService`** (`service/metrics/MetricCollectorService.java`)  
   - `@Scheduled(fixedRate = 60000)`: **1분마다** 버퍼를 집계해 `TrafficMetric` 엔티티로 만들고 `TrafficMetricRepository.save`  
   - TPS·평균/p95/p99 지연·에러율·요청/에러 건수·CPU(랜덤 추정)·힙 메모리 사용률·커넥션 풀(랜덤 추정) 포함  
   - 저장 후 해당 엔드포인트 버퍼 **리셋**  

3. **이상 탐지는 이 단계에서는 아님** — 집계 행은 처음에 `isAnomaly=false` 로 저장되고, `AnomalyDetectionService`가 이후 DB 행을 갱신합니다.

### 2.2 수동으로 트래픽 만들기 (예시)

**Linux / macOS**

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/products/product-001

curl -s -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d "{\"customerId\":\"customer-001\",\"productId\":\"product-001\",\"quantity\":1,\"totalPrice\":10000,\"paymentMethod\":\"CREDIT_CARD\"}"
```

**Windows PowerShell**

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/products/product-001" -Method Get

$body = @{
  customerId = "customer-001"
  productId  = "product-001"
  quantity   = 1
  totalPrice = 10000
  paymentMethod = "CREDIT_CARD"
} | ConvertTo-Json

Invoke-RestMethod -Uri "http://localhost:8080/api/orders" -Method Post -Body $body -ContentType "application/json"
```

**중요:** 집계는 **1분 주기**입니다. API를 호출한 뒤 **최소 1분 이상** 지난 뒤 3단계 조회를 하면 `traffic_metrics`에 행이 쌓인 것을 볼 수 있습니다.

---

## 3단계: 메트릭 조회 API

기본값으로 최근 구간을 조회합니다. 파라미터 `start`, `end`는 `ISO-8601` (`2026-03-31T12:00:00`).

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/api/metrics/traffic` | 시간 범위·엔드포인트 필터로 `TrafficMetric` 목록 |
| GET | `/api/metrics/anomalies` | `isAnomaly == true` 인 행 (이상 탐지 실행 후) |
| GET | `/api/metrics/pipeline/status` | 최근 1시간 행 수·이상 건수 등 (`DataPipelineService`) |
| GET | `/api/metrics/pipeline/statistics` | 최근 24시간 평균 TPS·지연·에러율 등 |
| GET | `/api/metrics/prometheus` | Prometheus **텍스트 포맷** (최근 1분 집계 행 기준) |

**예시**

```bash
curl -s "http://localhost:8080/api/metrics/traffic" | head -c 500
curl -s "http://localhost:8080/api/metrics/pipeline/status"
curl -s "http://localhost:8080/api/metrics/prometheus"
```

**Windows PowerShell**

```powershell
Invoke-RestMethod "http://localhost:8080/api/metrics/pipeline/status"
(Invoke-WebRequest "http://localhost:8080/api/metrics/prometheus").Content
```

**Prometheus 노출 이름 (커스텀 job)**

- `traffic_tps{endpoint="..."}`  — 라벨은 경로의 `/`가 `_`로 치환된 값  
- `traffic_latency_avg{endpoint="..."}`  
- `traffic_error_rate{endpoint="..."}`  
- `traffic_anomaly_score{endpoint="..."}`  — 이상 점수 없으면 `0`  

---

## 4단계: Prometheus · Grafana (Docker)

### 4.1 기동 (저장소 루트)

```bash
docker compose -f docker-compose-monitoring.yml up -d
```

**Windows PowerShell**

```powershell
docker compose -f docker-compose-monitoring.yml up -d
```

- Prometheus: `http://localhost:9090`  
- Grafana: `http://localhost:3000` (기본 계정 `admin` / `admin`)  

`prometheus` 서비스에 `extra_hosts: host.docker.internal:host-gateway` 가 있어 **Linux**에서도 호스트의 `8080` 스크랩이 동작하기 쉽습니다.

### 4.2 스크랩 설정 (`monitoring/prometheus/prometheus.yml`)

| job | metrics_path | 대상 |
|-----|----------------|------|
| `spring-boot-app` | `/actuator/prometheus` | Micrometer 기본 메트릭 |
| `traffic-metrics` | `/api/metrics/prometheus` | 이 장의 커스텀 트래픽 집계 |

타깃: `host.docker.internal:8080` — **호스트에서 `bootRun` 중**이어야 합니다.

### 4.3 Prometheus UI에서 조회하기 (Locust 해도 “안 보일 때”)

**전제:** 브라우저에서 `http://localhost:9090` → 상단 **Status → Targets** 에서 `spring-boot-app`, `traffic-metrics` 가 **UP** 인지 먼저 확인합니다. 하나라도 **DOWN**이면 스크랩이 안 되어 어떤 쿼리도 비어 보입니다.

#### 0. (필수) Prometheus용 Micrometer 레지스트리 의존성

`/actuator/prometheus` 가 **HTTP 요청 카운터(`http_server_requests_seconds_count` 등)** 를 내려주려면 **`io.micrometer:micrometer-registry-prometheus`** 가 classpath에 있어야 합니다.  
이 프로젝트는 `build.gradle`에 해당 의존성을 추가해 두었습니다. **의존성 추가 전에 빌드한 JAR로 실행 중이었다면** `./gradlew clean bootRun`(또는 Windows에서 `.\gradlew.bat clean bootRun`)으로 다시 띄운 뒤 아래를 확인하세요.

```bash
curl -s "http://localhost:8080/actuator/prometheus" | grep -E "http_server_requests_seconds_count|http_server_requests"
```

**Windows PowerShell**

```powershell
(Invoke-WebRequest "http://localhost:8080/actuator/prometheus").Content | Select-String "http_server_requests"
```

위 출력에 `http_server_requests_seconds_count` 가 **한 줄도 없으면** Prometheus/Grafana 쿼리는 당연히 비어 있습니다. 앱 재기동·의존성 반영 여부를 먼저 맞춥니다.

#### A. Locust 직후 바로 보이는 계열 — Micrometer (`job="spring-boot-app"`)

커스텀 `traffic_*` 와 달리, **`/actuator/prometheus`** 는 요청이 들어오는 즉시 카운터가 올라갑니다. Locust 실행 중 아래를 **Graph** 탭에 넣고 **Execute** 합니다.

```promql
sum by (uri, method) (rate(http_server_requests_seconds_count{job="spring-boot-app"}[1m]))
```

- **의미:** URI·메서드별 초당 요청 수(대략 RPS). Locust가 돌면 곡선이 움직입니다.  
- **이름이 다를 때:** Spring Boot / Micrometer 버전에 따라 시계열 이름이 조금 다를 수 있습니다. Prometheus 상단 검색창에 `http_server` 또는 `http` 만 입력해 **자동완성 목록**에서 `*_seconds_count` 형태를 고릅니다.  
- **더 단순한 확인:** 아래처럼 job만 맞춰 전체 합을 봅니다.

```promql
sum(rate(http_server_requests_seconds_count{job="spring-boot-app"}[1m]))
```

- **URI별 선이 안 나와도** `total RPS` 합계만 보이면 Locust 트래픽은 수집된 것입니다. (Grafana 첫 패널에 A=URI별, B=합계 두 쿼리를 둠.)

**스크랩 자체 확인(HTTP와 무관):**

```promql
process_uptime_seconds{job="spring-boot-app"}
```

값이 올라가면 `spring-boot-app` 타깃은 정상 스크랩 중입니다.

**호스트에서 직접 텍스트 확인 (메트릭 이름 찾기)**

**Linux / macOS**

```bash
curl -s "http://localhost:8080/actuator/prometheus" | head -n 40
curl -s "http://localhost:8080/actuator/prometheus" | grep -E "http_server|http\.server"
```

**Windows PowerShell**

```powershell
(Invoke-WebRequest "http://localhost:8080/actuator/prometheus").Content -split "`n" | Select-Object -First 40
(Invoke-WebRequest "http://localhost:8080/actuator/prometheus").Content | Select-String "http_server"
```

#### B. `traffic_tps` 등 커스텀 메트릭 (`job="traffic-metrics"`)

이 시계열은 **`MetricCollectorService`가 1분마다 DB에 집계한 뒤**, `/api/metrics/prometheus` 로만 노출됩니다.

- Locust를 켠 직후 `traffic_tps` 가 **비어 있는 것은 정상**일 수 있습니다. **최소 1분 이상** 부하를 유지한 뒤 다시 쿼리합니다.  
- 스크랩 주기(기본 15s)까지 감안하면 **약 1~2분 후** 점이 찍힙니다.  
- Graph 예시:

```promql
traffic_tps
```

**비어 있을 때 점검 순서**

1. `curl -s http://localhost:8080/api/metrics/prometheus` 에 `traffic_tps` 문자열이 있는지  
2. 없으면 `/api/metrics/traffic` 로 DB에 집계 행이 쌓였는지 (2단계·1분 주기)  
3. Prometheus **Targets** 에서 `traffic-metrics` 가 **UP** 인지  

---

### 4.4 Grafana — `traffic-metrics.json` (Import가 “무반응”이던 이유와 해결)

#### 왜 예전 파일로는 Import가 안 됐나

Grafana **Dashboard → Import** 는 다음 중 하나를 기대합니다.

- Grafana **Export** 로 내려받은 것과 같이, **루트에 `title`, `panels`, `schemaVersion` 등이 있는 JSON** (단일 대시보드 객체)  
- 또는 구 API용으로 **`{"dashboard":{...}, "overwrite":true}`** 처럼 **객체 전체를 처리하는 엔드포인트**에 맞는 형태  

기존 `traffic-metrics.json` 은 `"dashboard": { "title": ... "panels": [ { "type": "graph" } ] }` 만 있고 **`schemaVersion`·`gridPos`·`datasource.uid` 등이 없는 비표준 형태**라, 붙여넣기 후 **검증 실패 또는 로딩 없음**처럼 보일 수 있습니다.

#### 방법 1: 파일 교체 후 자동 로드 (권장)

저장소에는 다음이 포함됩니다.

- `monitoring/grafana/provisioning/datasources/prometheus.yml` — 데이터 소스 **`uid: prometheus`** (패널과 연결)  
- `monitoring/grafana/provisioning/dashboards/default.yml` — `/var/lib/grafana/dashboards` 의 JSON 자동 로드  
- `monitoring/grafana/dashboards/traffic-metrics.json` — Grafana 9+ `timeseries` 패널 형식의 **유효한 대시보드 JSON**

**처음 적용하거나 JSON을 수정한 뒤에는 Grafana를 재시작합니다.**

```bash
docker compose -f docker-compose-monitoring.yml up -d --force-recreate grafana
```

**Windows PowerShell**

```powershell
docker compose -f docker-compose-monitoring.yml up -d --force-recreate grafana
```

브라우저에서 **Dashboards** 메뉴 → 폴더 **Ch07** → **Traffic Metrics (Ch07)** 가 보이면 성공입니다.

> 이전에 올린 Grafana **볼륨**에 깨진 대시보드/데이터 소스가 남아 있으면, `docker compose -f docker-compose-monitoring.yml down` 후 Grafana 볼륨을 비우고 다시 `up` 하는 것이 안전할 수 있습니다.

#### 방법 2: 수동 Import (UI)

1. `http://localhost:3000` 로그인 (`admin` / `admin`)  
2. 왼쪽 메뉴 **Dashboards** → **New** → **Import**  
3. **Upload dashboard JSON file** 에서 저장소의 `monitoring/grafana/dashboards/traffic-metrics.json` 선택 **또는** 파일 내용 전체를 **Paste JSON** 에 붙여넣기  
4. **Prometheus** 데이터 소스를 선택(기본값이면 그대로) 후 **Import** 클릭  

**주의:** 붙여넣기 후 **반드시 하단의 Import 버튼**을 눌러야 합니다. JSON이 잘리면 파싱 오류로 아무 변화가 없을 수 있습니다.

대시보드 첫 번째 패널은 **Locust와 바로 연동되는 Micrometer RPS** 쿼리를 사용합니다. 나머지는 `traffic_*` (1분 집계)입니다.

---

## 5단계: AI(통계) 이상 탐지 · 부하 실습

### 5.1 동작 (`service/ai/AnomalyDetectionService.java`)

| 스케줄 | 내용 |
|--------|------|
| `learnNormalPattern` | 앱 기동 **약 90초 후** 첫 실행, 이후 **1시간마다** — 최근 1시간 집계 행으로 평균·표준편차 학습 |
| `detectAnomalies` | **1분마다** — 최근 1분 이내 저장된 행에 대해 Z-score·에러율·CPU·메모리 임계값으로 점수 합산, **0.5 초과 시** `isAnomaly`·`anomalyScore`·`anomalyReason` 업데이트 |

**실습 팁:** 정상 트래픽으로 몇 분 돌린 뒤, **Locust 병목 시나리오**로 트래픽을 키우면 TPS·지연·에러율이 벗어나 이상치로 잡히기 쉽습니다.

### 5.2 부하 예시 (`locust/`)

**Linux / macOS**

```bash
cd locust
source venv/bin/activate
python -m locust -f load_test_bottleneck.py \
  --host=http://localhost:8080 --headless \
  --users=200 --spawn-rate=20 --run-time=5m
```

**Windows PowerShell**

```powershell
cd locust
python -m locust -f load_test_bottleneck.py --host=http://localhost:8080 --headless --users=200 --spawn-rate=20 --run-time=5m
```

### 5.3 확인

```bash
curl -s "http://localhost:8080/api/metrics/anomalies"
```

이상으로 표시된 행이 Prometheus에 반영되려면, 스크랩 주기(15s) 이후 `traffic_anomaly_score` 를 확인합니다.

---

## 개념 정리 (참고)

### 핵심 메트릭

- **TPS**: 집계 윈도우(인터셉터 버퍼) 내 요청 수 ÷ 경과 초  
- **Latency**: 동일 윈도우 내 응답 시간 평균·p95·p99  
- **Error Rate**: HTTP 기준 실패(4xx/5xx 등 `success==false`) 비율  
- **Resource**: 코드상 CPU·커넥션 풀은 **추정/샘플**이며, 운영 관측은 Actuator/Micrometer를 병행하는 것이 좋습니다.

### 데이터 파이프라인 (구현 매핑)

```
수집(인터셉터·메모리) → 저장(1분 스케줄·JPA) → 처리(이상 탐지 스케줄·엔티티 갱신) → 시각화(Prometheus 스크랩·Grafana)
```

- 파이프라인 **상태 요약**: `service/pipeline/DataPipelineService.java`  
- **AI** 표현은 통계적 이상 탐지(Z-score·임계값)에 해당합니다.

---

## 코드 구조 (패키지)

```
domain/metrics/TrafficMetric.java
repository/metrics/TrafficMetricRepository.java
service/metrics/MetricCollectorService.java
service/ai/AnomalyDetectionService.java
service/pipeline/DataPipelineService.java
controller/metrics/TrafficMetricsController.java
interceptor/MetricInterceptor.java
config/MetricsConfig.java
config/WebConfig.java
```

---

## 트러블슈팅

| 증상 | 점검 |
|------|------|
| `/api/metrics/traffic` 이 비어 있음 | 1분 스케줄 전·`/api/` 호출 부족 — 호출 후 1분 이상 대기 |
| 이상 탐지가 안 됨 | 기동 후 **약 90초** 뒤 첫 학습 필요. 학습 시점에 DB 집계 행이 거의 없으면 `learnNormalPattern` 이 스킵될 수 있음 — 트래픽 후 재시도 |
| Prometheus `DOWN` | 호스트에서 `8080` 기동 여부, `host.docker.internal` (Linux는 compose의 `extra_hosts` 확인) |
| Grafana에 시계열 없음 | 우측 상단 시간 범위를 **Last 15 minutes** 등으로 확대. Locust 중에는 **`http_server_requests_seconds_count` + rate** 로 먼저 확인 (4.3절 A) |
| Locust인데 `traffic_*` 만 없음 | **1분 집계 + 스크랩 지연** — 4.3절 B. Micrometer 패널/쿼리는 즉시 반응해야 함 |
| `http_server_requests_*` 자체가 없음 | **`micrometer-registry-prometheus` 미포함 빌드로 기동** — 4.3절 0, `clean bootRun` 후 `actuator/prometheus` 에서 문자열 확인 |
| Grafana Import 후 무반응 | JSON이 구버전 래핑-only 형식이면 실패 — `traffic-metrics.json` 최신본 사용, **Import 버튼** 클릭 여부 확인 |
| 프로비저닝 대시보드가 안 보임 | `docker compose ... --force-recreate grafana` 또는 Grafana 볼륨 초기화 후 재기동 (4.4절) |

---

## 다음 단계 (선택)

- 실제 CPU·풀 사용률: OSHI, Hikari 메트릭 등과 연동  
- 이상 탐지: 다변량·LSTM 등 고급 모델  
- 알림(Alertmanager)·SLI/SLO 패널 확장  

---

## 참고 자료

- [Prometheus](https://prometheus.io/docs/)  
- [Grafana](https://grafana.com/docs/)  
- [Z-score](https://en.wikipedia.org/wiki/Standard_score)  

---

## 체크리스트

- [ ] Redis·JDK 준비 후 `bootRun` 성공  
- [ ] `/api/...` 호출 후 1분 뒤 `/api/metrics/traffic` 에 행 확인  
- [ ] `/api/metrics/pipeline/status` 로 파이프라인 요약 확인  
- [ ] (선택) Docker 모니터링 스택 기동 후 Prometheus **Targets** UP  
- [ ] (선택) Prometheus **Graph** 에서 `rate(http_server_requests_seconds_count{job="spring-boot-app"}[1m])` 실행 (Locust 중)  
- [ ] (선택) Grafana 폴더 **Ch07** 또는 Import 로 `traffic-metrics.json` 확인  
- [ ] (선택) 1분 후 `traffic_tps` 등 커스텀 시계열 확인  
- [ ] 부하 후 `/api/metrics/anomalies` 또는 `traffic_anomaly_score` 확인  
