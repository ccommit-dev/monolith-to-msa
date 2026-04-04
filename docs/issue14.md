# Ch07.02: AI 이상징후 탐지

## 실행 요약(현행 코드 기준)
- 이 문서는 고급(AI 고도화) 설계까지 담고 있으나, 현재 저장소 코드로 즉시 실행 가능한 흐름은 아래와 같습니다.
- 아래 0~5단계를 먼저 수행해 이상 탐지를 재현하세요. 이후 본문(고급 설계)은 로드맵으로 참고하세요.

### 현행 실행 순서
1) 앱 기동
```bash
./gradlew bootRun
```
**Windows PowerShell**
```powershell
.\gradlew.bat bootRun
```
2) 트래픽 발생(예시)
```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/products/product-001
curl -s -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId":"customer-001","productId":"product-001","quantity":1,"totalPrice":10000,"paymentMethod":"CREDIT_CARD"}'
```
**Windows PowerShell**
```powershell
# GET 예시
Invoke-RestMethod -Uri "http://localhost:8080/api/products/product-001" -Method Get

# POST 예시
$body = @{
  customerId = "customer-001"
  productId  = "product-001"
  quantity   = 1
  totalPrice = 10000
  paymentMethod = "CREDIT_CARD"
} | ConvertTo-Json

Invoke-RestMethod -Uri "http://localhost:8080/api/orders" -Method Post -Body $body -ContentType "application/json"
```
3) 1분 이상 대기 후 메트릭/파이프라인 확인
```bash
curl -s "http://localhost:8080/api/metrics/traffic" | head -c 400
curl -s "http://localhost:8080/api/metrics/pipeline/status"
```
**Windows PowerShell**
```powershell
(Invoke-WebRequest "http://localhost:8080/api/metrics/traffic").Content
Invoke-RestMethod "http://localhost:8080/api/metrics/pipeline/status"
```
4) (선택) Prometheus/Grafana 기동
```bash
docker compose -f docker-compose-monitoring.yml up -d
```
**Windows PowerShell**
```powershell
docker compose -f docker-compose-monitoring.yml up -d
```
5) 이상 탐지 관찰
- 앱 기동 후 약 90초 지나면 첫 학습이 수행되고, 이후 1분마다 이상 탐지가 동작합니다.
```bash
curl -s "http://localhost:8080/api/metrics/anomalies"
```
**Windows PowerShell**
```powershell
Invoke-RestMethod "http://localhost:8080/api/metrics/anomalies"
```
6) Prometheus 텍스트 확인(커스텀 메트릭)
```bash
curl -s "http://localhost:8080/api/metrics/prometheus"
```
**Windows PowerShell**
```powershell
(Invoke-WebRequest "http://localhost:8080/api/metrics/prometheus").Content
```

### 현행 엔드포인트(구현되어 있음)
- GET `/api/metrics/traffic`, `/api/metrics/anomalies`
- GET `/api/metrics/pipeline/status`, `/api/metrics/pipeline/statistics`
- GET `/api/metrics/prometheus`

주의: 본문에 등장하는 `/api/ai/...` 계열(예: baseline/alerts/connection-pool/status)은 현재 코드에 구현되어 있지 않은 로드맵 항목입니다. 실행 시에는 위 “현행 엔드포인트”를 사용하세요.

## 실습 목표
- Baseline Learning: 7일간의 정상 패턴 학습 및 기준선 설정
- AI 자동 감지: 실시간 모니터링 → 이상 자동 감지 → 알림
- Part 2/3 병목 자동 감지: AI가 커넥션 풀 고갈 자동 탐지
- False Positive 최소화: 오탐 30% → 5%로 감소

---

## 1. 정상 패턴 학습 (Baseline Learning)

### 개요
- **학습 기간**: 최근 7일간의 정상 데이터
- **학습 주기**: 매일 자정에 자동 학습
- **학습 데이터**: 이상치로 판단되지 않은 정상 메트릭만 사용
- **최소 샘플 수**: 100개 이상

### Baseline 패턴 구성

#### TPS 기준선
- 평균 TPS
- 표준편차
- 최소/최대 TPS 범위

#### Latency 기준선
- 평균 Latency
- 표준편차
- 최소/최대 Latency 범위

#### Error Rate 기준선
- 평균 Error Rate
- 표준편차

#### Resource 기준선
- CPU 사용률 (평균, 표준편차)
- Memory 사용률 (평균, 표준편차)
- Connection Pool 사용률 (평균, 표준편차)

### 학습 프로세스

```
1. 최근 7일간의 메트릭 데이터 조회
2. 이상치 제외 (isAnomaly = false 또는 null)
3. 통계 계산 (평균, 표준편차, 최소/최대)
4. Baseline 패턴 저장
5. False Positive 최소화를 위한 임계값 설정 (기본 0.7)
```

---

## 2. 이상 징후 자동 탐지 알고리즘

### 향상된 이상 탐지 방법

#### 1. Baseline 기반 Z-score 계산
- **기존**: 단순 Z-score > 2.0
- **개선**: Baseline의 평균과 표준편차 기반 Z-score > 2.5
- **가중치**: TPS(0.25), Latency(0.25), Error Rate(0.2), Connection Pool(0.15), Resource(0.15)

#### 2. 신뢰도 기반 판단
- **신뢰도 계산**: 각 이상치 지표의 신뢰도 합산
- **임계값**: 신뢰도 >= 0.8 이상일 때만 이상치로 판단
- **효과**: False Positive 감소

#### 3. 연속 이상치 확인
- **최소 연속 이상치**: 3개 이상
- **시간 범위**: 최근 5분간
- **효과**: 일시적인 스파이크는 무시, 지속적인 이상만 감지

### 이상 탐지 프로세스

```
1. 최근 1분간의 메트릭 조회
2. 각 메트릭에 대해 Baseline 패턴 조회
3. Z-score 계산 및 이상치 점수 계산
4. 신뢰도 계산
5. 연속 이상치 확인
6. 이상치로 판단되면 DB 업데이트 및 알림 발송
```

---

## 3. Part 2/3 병목 자동 감지

### 커넥션 풀 고갈 감지

#### 감지 조건
- **위험 임계값**: 커넥션 풀 사용률 >= 90%
- **경고 임계값**: 커넥션 풀 사용률 >= 80%
- **대기 스레드**: threadsAwaitingConnection > 0

#### 모니터링 주기
- **체크 주기**: 30초마다
- **HikariCP 메트릭**: 실시간 커넥션 풀 상태 조회

#### 감지 프로세스

```
1. HikariCP PoolMXBean을 통한 커넥션 풀 상태 조회
2. 사용률 계산: (활성 + 유휴) / 최대 커넥션 수 * 100
3. 위험 상태 감지:
   - 사용률 >= 90% 또는 대기 스레드 > 0
4. 최근 메트릭의 커넥션 풀 사용률 업데이트
5. 알림 발송
```

### 커넥션 풀 메트릭

- **활성 커넥션**: 현재 사용 중인 커넥션 수
- **유휴 커넥션**: 사용 가능한 커넥션 수
- **전체 커넥션**: 활성 + 유휴
- **최대 커넥션**: maximum-pool-size
- **대기 스레드**: 커넥션 획득 대기 중인 스레드 수

---

## 4. False Positive 최소화 전략

### 문제점
- **기존 오탐률**: 30%
- **원인**: 단순 임계값 기반 판단, 일시적 스파이크 오탐

### 개선 전략

#### 1. 임계값 상향 조정
- **기존**: 0.5
- **개선**: 0.7 (Baseline 기반)
- **효과**: 더 확실한 이상만 감지

#### 2. 신뢰도 기반 필터링
- **신뢰도 임계값**: 0.8 이상
- **효과**: 낮은 신뢰도의 이상치는 무시

#### 3. 연속 이상치 확인
- **최소 연속 개수**: 3개
- **시간 범위**: 5분
- **효과**: 일시적 스파이크는 무시

#### 4. Baseline 기반 정교한 판단
- **Z-score 임계값**: 2.0 → 2.5로 상향
- **범위 기반 검증**: Baseline의 최소/최대 범위 확인
- **효과**: 정상 범위 내 변동은 무시

#### 5. 알림 쿨다운
- **쿨다운 시간**: 5분
- **효과**: 중복 알림 방지

### 개선 효과

| 전략 | 오탐률 감소 |
|------|------------|
| 임계값 상향 (0.5 → 0.7) | -10% |
| 신뢰도 필터링 (>= 0.8) | -8% |
| 연속 이상치 확인 (3개) | -5% |
| Z-score 임계값 상향 (2.0 → 2.5) | -2% |
| **총합** | **-25% (30% → 5%)** |

---

## 실습 순서

중요: 아래 실습 순서는 “고도화 설계” 예시입니다. 현재 저장소에는 `/api/ai/...` 엔드포인트가 없으므로, 실제 실행은 문서 상단의 “현행 실행 순서”와 `issue13.md`의 절차를 따르세요. 고급 항목은 로드맵 참고용입니다.

### 1단계: Baseline 패턴 학습

#### 1.1 데이터 수집 (7일간)
```bash
# 애플리케이션 실행
./gradlew bootRun

# 정상 트래픽 생성 (7일간)
# Locust 부하 테스트 또는 실제 사용자 트래픽
```

#### 1.2 Baseline 패턴 수동 학습
```bash
# 특정 엔드포인트 학습
curl -X POST "http://localhost:8080/api/ai/baseline/learn?endpoint=/api/orders"

# 모든 엔드포인트 학습
curl -X POST "http://localhost:8080/api/ai/baseline/learn"
```

#### 1.3 Baseline 패턴 확인
```bash
# Baseline 패턴 조회
curl "http://localhost:8080/api/ai/baseline?endpoint=/api/orders"
```

---

### 2단계: 이상 탐지 테스트

#### 2.1 정상 트래픽 모니터링
```bash
# 정상 트래픽 생성
cd locust
source venv/bin/activate
locust -f load_test.py \
    --host=http://localhost:8080 \
    --headless \
    --users=50 \
    --spawn-rate=5 \
    --run-time=10m
```

#### 2.2 이상 트래픽 생성
```bash
# 부하 테스트로 이상 트래픽 생성
locust -f load_test_bottleneck.py \
    --host=http://localhost:8080 \
    --headless \
    --users=200 \
    --spawn-rate=20 \
    --run-time=5m
```

#### 2.3 이상치 감지 확인
```bash
# 이상치 메트릭 조회
curl "http://localhost:8080/api/ai/anomalies"

# 최근 1시간 이상치 조회
curl "http://localhost:8080/api/ai/anomalies?start=2024-01-01T00:00:00&end=2024-01-01T23:59:59"
```

---

### 3단계: 커넥션 풀 고갈 감지 테스트

#### 3.1 커넥션 풀 상태 확인
```bash
# 커넥션 풀 상태 조회
curl "http://localhost:8080/api/ai/connection-pool/status"
```

#### 3.2 고갈 상황 재현
```bash
# 높은 부하로 커넥션 풀 고갈 재현
locust -f load_test_bottleneck.py \
    --host=http://localhost:8080 \
    --headless \
    --users=300 \
    --spawn-rate=30 \
    --run-time=10m
```

#### 3.3 자동 감지 확인
- 애플리케이션 로그에서 커넥션 풀 고갈 알림 확인
- 이상치 메트릭에서 커넥션 풀 사용률 확인

---

### 4단계: False Positive 최소화 검증

#### 4.1 정상 트래픽에서 오탐 확인
```bash
# 정상 트래픽 생성
locust -f load_test.py \
    --host=http://localhost:8080 \
    --headless \
    --users=50 \
    --spawn-rate=5 \
    --run-time=30m
```

#### 4.2 오탐률 계산
```bash
# 전체 메트릭 수
total_metrics = curl "http://localhost:8080/api/metrics/traffic" | jq '. | length'

# 이상치 메트릭 수
anomaly_metrics = curl "http://localhost:8080/api/ai/anomalies" | jq '. | length'

# 오탐률 = (이상치 수 / 전체 수) * 100
# 목표: 5% 이하
```

---

## 코드 구조

### 도메인 모델
```
domain/
├── baseline/
│   └── BaselinePattern.java          # Baseline 패턴 엔티티
└── metrics/
    └── TrafficMetric.java            # 트래픽 메트릭 엔티티
```

### Repository
```
repository/
├── baseline/
│   └── BaselinePatternRepository.java
└── metrics/
    └── TrafficMetricRepository.java
```

### 서비스
```
service/
├── baseline/
│   └── BaselineLearningService.java  # Baseline 학습 서비스
├── ai/
│   └── EnhancedAnomalyDetectionService.java  # 향상된 이상 탐지
├── connection/
│   └── ConnectionPoolMonitorService.java     # 커넥션 풀 모니터링
└── alert/
    └── AlertService.java             # 알림 서비스
```

### 컨트롤러
```
controller/ai/
└── AnomalyDetectionController.java  # AI 이상 탐지 API
```

---

## API 엔드포인트

### Baseline 패턴
```
GET /api/ai/baseline
  - endpoint: 엔드포인트 필터 (선택)

POST /api/ai/baseline/learn
  - endpoint: 엔드포인트 필터 (선택, 없으면 전체)
```

### 이상 탐지
```
GET /api/ai/anomalies
  - start: 시작 시간 (ISO 8601)
  - end: 종료 시간 (ISO 8601)
```

### 커넥션 풀 모니터링
```
GET /api/ai/connection-pool/status
  - 커넥션 풀 상태 조회
```

### 알림 통계
```
GET /api/ai/alerts/statistics
  - 알림 통계 조회
```

---

## 핵심 메시지

### 1. Baseline Learning은 정확한 이상 탐지의 기초
- **7일간의 정상 패턴 학습**: 충분한 데이터로 정확한 기준선 설정
- **이상치 제외**: 학습 데이터에서 이상치 제외로 정확도 향상
- **통계 기반 기준선**: 평균, 표준편차, 최소/최대 범위로 정교한 판단

### 2. AI는 패턴 인식 도구
- **Baseline 기반 판단**: 단순 임계값이 아닌 패턴 기반 판단
- **신뢰도 기반 필터링**: 확실한 이상만 감지
- **연속 이상치 확인**: 지속적인 이상만 감지

### 3. False Positive 최소화는 실용성의 핵심
- **다층 필터링**: 임계값, 신뢰도, 연속성 다중 검증
- **오탐률 감소**: 30% → 5%로 대폭 감소
- **실용성 향상**: 실제 운영 환경에서 사용 가능

### 4. 자동화는 운영 효율성의 핵심
- **자동 감지**: 수동 모니터링에서 자동 감지로 전환
- **자동 알림**: 이상 상황 자동 알림
- **자동 학습**: 매일 자정에 자동 Baseline 재학습

---

## 다음 단계

### 1. 고급 이상 탐지
- **머신러닝 모델**: LSTM, Autoencoder 등
- **다변량 분석**: 여러 메트릭 동시 분석
- **시계열 예측**: 미래 이상 예측

### 2. 실시간 스트리밍
- **Kafka + Flink**: 실시간 스트림 처리
- **이벤트 기반 알림**: 즉시 알림 발송
- **자동 복구**: 이상 감지 시 자동 조치

### 3. 예측 분석
- **트래픽 예측**: 미래 트래픽 예측
- **리소스 예측**: 리소스 사용량 예측
- **장애 예측**: 장애 발생 가능성 예측

---

## 참고 자료

- [Z-score 이상 탐지](https://en.wikipedia.org/wiki/Standard_score)
- [HikariCP 메트릭](https://github.com/brettwooldridge/HikariCP)
- [시계열 이상 탐지](https://www.kaggle.com/code/victorambonati/anomaly-detection-time-series)

---

## 체크리스트

### Baseline Learning
- [ ] 7일간의 정상 데이터 수집
- [ ] Baseline 패턴 학습 실행
- [ ] Baseline 패턴 확인
- [ ] 자동 학습 스케줄 확인

### 이상 탐지
- [ ] 향상된 이상 탐지 알고리즘 구현
- [ ] 신뢰도 기반 필터링 구현
- [ ] 연속 이상치 확인 구현
- [ ] 이상 탐지 테스트

### 커넥션 풀 모니터링
- [ ] HikariCP 메트릭 수집 구현
- [ ] 커넥션 풀 고갈 감지 구현
- [ ] 자동 알림 구현
- [ ] 고갈 상황 재현 테스트

### False Positive 최소화
- [ ] 임계값 상향 조정
- [ ] 신뢰도 필터링 구현
- [ ] 연속 이상치 확인 구현
- [ ] 오탐률 측정 및 검증
