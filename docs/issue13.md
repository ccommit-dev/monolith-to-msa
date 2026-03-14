# Ch07.01: AI 트래픽 데이터 수집

## 실습 목표
- 핵심 메트릭 수집 (TPS, Latency, Error Rate, Resource)
- 데이터 파이프라인 구축 (수집 → 저장 → 처리 → 시각화)
- AI 기반 이상 탐지 구현
- Grafana 대시보드를 통한 인사이트 시각화

---

## 핵심 메트릭

### 1. TPS (Transactions Per Second)
- **정의**: 초당 처리되는 트랜잭션 수
- **수집 방법**: 시간 윈도우 내 요청 수 / 시간
- **용도**: 시스템 처리량 측정, 부하 모니터링

### 2. Latency (응답 시간)
- **정의**: 요청 처리에 걸리는 시간
- **수집 방법**: 평균, P95, P99 응답 시간
- **용도**: 성능 모니터링, 사용자 경험 측정

### 3. Error Rate (에러율)
- **정의**: 전체 요청 중 에러 발생 비율
- **수집 방법**: 에러 수 / 전체 요청 수 * 100
- **용도**: 시스템 안정성 측정, 장애 감지

### 4. Resource (리소스 사용률)
- **정의**: CPU, Memory, Connection Pool 사용률
- **수집 방법**: 시스템 메트릭 수집
- **용도**: 리소스 모니터링, 확장성 판단

---

## 데이터 파이프라인

### 파이프라인 구조

```
수집 → 저장 → 처리 → 시각화
```

#### 1. 수집 (Collection)
- **구현**: `MetricInterceptor` + `MetricCollectorService`
- **방법**: HTTP 요청 인터셉터를 통한 실시간 메트릭 수집
- **주기**: 실시간 수집, 1분마다 집계

#### 2. 저장 (Storage)
- **구현**: `TrafficMetricRepository` + H2 Database
- **방법**: JPA를 통한 메트릭 데이터 영구 저장
- **형식**: `TrafficMetric` 엔티티

#### 3. 처리 (Processing)
- **구현**: `AnomalyDetectionService`
- **방법**: AI 기반 이상 탐지 (Z-score 기반)
- **주기**: 1분마다 이상 탐지 수행

#### 4. 시각화 (Visualization)
- **구현**: Grafana + Prometheus
- **방법**: Prometheus 메트릭 수집, Grafana 대시보드
- **형식**: 실시간 대시보드, 알림 설정

---

## AI 이상 탐지

### 이상 탐지 알고리즘

#### 1. 정상 패턴 학습
- **기간**: 최근 1시간 데이터
- **통계**: 평균, 표준편차 계산
- **주기**: 1시간마다 재학습

#### 2. 이상치 감지 (Z-score 기반)
- **TPS 이상치**: Z-score > 2.0
- **Latency 이상치**: Z-score > 2.0
- **Error Rate 이상치**: 평균 + 2*표준편차 초과
- **Resource 이상치**: CPU > 80%, Memory > 85%

#### 3. 이상치 점수 계산
- **점수 범위**: 0.0 ~ 1.0
- **임계값**: 0.5 이상 시 이상치로 판단
- **가중치**:
  - TPS 이상: 0.3
  - Latency 이상: 0.3
  - Error Rate 이상: 0.2
  - Resource 이상: 0.1 (CPU/Memory 각각)

---

## 인사이트 대시보드

### Grafana 대시보드 구성

#### 1. TPS 대시보드
- **패널**: TPS 그래프 (엔드포인트별)
- **메트릭**: `traffic_tps{endpoint="..."}`
- **용도**: 처리량 모니터링

#### 2. Latency 대시보드
- **패널**: 평균 응답 시간 그래프
- **메트릭**: `traffic_latency_avg{endpoint="..."}`
- **용도**: 성능 모니터링

#### 3. Error Rate 대시보드
- **패널**: 에러율 그래프
- **메트릭**: `traffic_error_rate{endpoint="..."}`
- **용도**: 안정성 모니터링

#### 4. 이상 탐지 대시보드
- **패널**: 이상치 점수 테이블
- **메트릭**: `traffic_anomaly_score{endpoint="..."}`
- **용도**: 이상치 감지 및 알림

---

## 실습 순서

### 1단계: 메트릭 수집 설정

#### 1.1 데이터베이스 마이그레이션
```bash
# 애플리케이션 실행 시 자동으로 테이블 생성
# V2__create_traffic_metrics_table.sql 실행
```

#### 1.2 인터셉터 등록 확인
- `WebConfig`에 `MetricInterceptor` 등록 확인
- `/api/**` 경로에 인터셉터 적용

#### 1.3 스케줄링 활성화
- `MetricsConfig`에서 `@EnableScheduling` 확인

---

### 2단계: 메트릭 수집 테스트

#### 2.1 애플리케이션 실행
```bash
./gradlew bootRun
```

#### 2.2 API 요청 생성
```bash
# 주문 생성 요청
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "customer-001",
    "productId": "product-001",
    "quantity": 1,
    "totalPrice": 10000,
    "paymentMethod": "CREDIT_CARD"
  }'

# 상품 조회 요청
curl http://localhost:8080/api/products/product-001
```

#### 2.3 메트릭 조회
```bash
# 최근 1시간 메트릭 조회
curl http://localhost:8080/api/metrics/traffic

# 이상치 메트릭 조회
curl http://localhost:8080/api/metrics/anomalies

# 파이프라인 상태 조회
curl http://localhost:8080/api/metrics/pipeline/status
```

---

### 3단계: Prometheus 설정

#### 3.1 Prometheus 실행
```bash
# Docker Compose로 실행
docker compose -f docker-compose-monitoring.yml up -d prometheus

# Prometheus 접속
# http://localhost:9090
```

#### 3.2 메트릭 수집 확인
- Prometheus UI에서 `traffic_tps` 메트릭 확인
- `traffic_latency_avg` 메트릭 확인
- `traffic_error_rate` 메트릭 확인

---

### 4단계: Grafana 설정

#### 4.1 Grafana 실행
```bash
# Docker Compose로 실행
docker compose -f docker-compose-monitoring.yml up -d grafana

# Grafana 접속
# http://localhost:3000
# ID: admin, Password: admin
```

#### 4.2 데이터 소스 연결
- Prometheus 데이터 소스 자동 연결 확인
- `http://prometheus:9090` 연결 확인

#### 4.3 대시보드 생성
- Traffic Metrics Dashboard 임포트
- 또는 수동으로 패널 생성

---

### 5단계: AI 이상 탐지 테스트

#### 5.1 정상 패턴 학습 대기
- 최소 1시간 이상 데이터 수집 필요
- 정상 트래픽 패턴 학습

#### 5.2 이상 트래픽 생성
```bash
# 부하 테스트로 이상 트래픽 생성
cd locust
source venv/bin/activate
locust -f load_test_bottleneck.py \
    --host=http://localhost:8080 \
    --headless \
    --users=200 \
    --spawn-rate=20 \
    --run-time=5m
```

#### 5.3 이상치 감지 확인
```bash
# 이상치 메트릭 조회
curl http://localhost:8080/api/metrics/anomalies

# 이상치 점수 및 원인 확인
```

---

## 코드 구조

### 도메인 모델
```
domain/metrics/
└── TrafficMetric.java          # 트래픽 메트릭 엔티티
```

### Repository
```
repository/metrics/
└── TrafficMetricRepository.java # 메트릭 Repository
```

### 서비스
```
service/
├── metrics/
│   └── MetricCollectorService.java    # 메트릭 수집 서비스
├── ai/
│   └── AnomalyDetectionService.java   # AI 이상 탐지 서비스
└── pipeline/
    └── DataPipelineService.java       # 데이터 파이프라인 서비스
```

### 컨트롤러
```
controller/metrics/
└── TrafficMetricsController.java      # 메트릭 조회 API
```

### 인터셉터
```
interceptor/
└── MetricInterceptor.java             # 메트릭 수집 인터셉터
```

### 설정
```
config/
└── MetricsConfig.java                 # 메트릭 수집 설정
```

---

## API 엔드포인트

### 메트릭 조회
```
GET /api/metrics/traffic
  - start: 시작 시간 (ISO 8601)
  - end: 종료 시간 (ISO 8601)
  - endpoint: 엔드포인트 필터 (선택)

GET /api/metrics/anomalies
  - start: 시작 시간 (ISO 8601)
  - end: 종료 시간 (ISO 8601)

GET /api/metrics/pipeline/status
  - 파이프라인 상태 조회

GET /api/metrics/pipeline/statistics
  - 파이프라인 통계 조회

GET /api/metrics/prometheus
  - Prometheus 형식 메트릭 조회
```

---

## 핵심 메시지

### 1. 측정 가능한 것만 관리할 수 있다
- **메트릭 수집**: 모든 성능 지표를 수집해야 함
- **데이터 기반 의사결정**: 추측이 아닌 데이터로 판단
- **지속적인 모니터링**: 실시간 모니터링 필수

### 2. 데이터 파이프라인은 핵심 인프라
- **수집 → 저장 → 처리 → 시각화**: 전체 파이프라인 구축
- **확장 가능한 구조**: 대용량 데이터 처리 가능
- **실시간 처리**: 지연 시간 최소화

### 3. AI는 패턴 인식 도구
- **정상 패턴 학습**: 과거 데이터로 정상 패턴 학습
- **이상치 감지**: 통계적 방법으로 이상치 감지
- **자동화**: 수동 모니터링에서 자동 감지로 전환

### 4. 시각화는 인사이트 도구
- **대시보드**: 한눈에 보는 시스템 상태
- **알림**: 이상 상황 자동 알림
- **트렌드 분석**: 시간에 따른 변화 추적

---

## 다음 단계

### 1. 고급 이상 탐지
- **머신러닝 모델**: LSTM, Autoencoder 등
- **실시간 스트리밍**: Kafka + Flink
- **다변량 분석**: 여러 메트릭 동시 분석

### 2. 예측 분석
- **트래픽 예측**: 미래 트래픽 예측
- **리소스 예측**: 리소스 사용량 예측
- **장애 예측**: 장애 발생 가능성 예측

### 3. 자동화
- **자동 스케일링**: 트래픽에 따른 자동 확장
- **자동 복구**: 장애 시 자동 복구
- **자동 최적화**: 성능 자동 최적화

---

## 참고 자료

- [Prometheus 공식 문서](https://prometheus.io/docs/)
- [Grafana 공식 문서](https://grafana.com/docs/)
- [Z-score 이상 탐지](https://en.wikipedia.org/wiki/Standard_score)
- [시계열 이상 탐지](https://www.kaggle.com/code/victorambonati/anomaly-detection-time-series)

---

## 체크리스트

### 메트릭 수집
- [ ] 메트릭 수집 인터셉터 등록
- [ ] 메트릭 수집 서비스 구현
- [ ] 데이터베이스 테이블 생성
- [ ] 메트릭 수집 테스트

### 데이터 파이프라인
- [ ] 수집 단계 구현
- [ ] 저장 단계 구현
- [ ] 처리 단계 구현
- [ ] 시각화 단계 구현

### AI 이상 탐지
- [ ] 정상 패턴 학습 구현
- [ ] 이상치 감지 알고리즘 구현
- [ ] 이상치 점수 계산 구현
- [ ] 이상치 감지 테스트

### Grafana 대시보드
- [ ] Prometheus 설정
- [ ] Grafana 설정
- [ ] 대시보드 생성
- [ ] 알림 설정
