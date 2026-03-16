# Ch06.07: Locust 부하 테스트

## 실습 목표
- Locust를 사용한 부하 테스트 구현
- Ramp-up → Steady → Ramp-down 시나리오 작성
- Locust 웹 대시보드를 통한 실시간 모니터링
- H2 DB 연동 및 성능 메트릭 수집
- 베이스라인 설정 (TPS 2,000, 응답 500ms, 에러율 1%)

---

## 전체 구조 (50분 로드맵)

### 1단계: Locust 설치 및 기본 구조 (10분)
- Python 가상 환경 설정
- Locust 설치
- 기본 스크립트 구조 이해

### 2단계: 시나리오 작성 (15분)
- 상품 조회 시나리오
- 주문 생성 시나리오
- 결제 처리 시나리오
- Ramp-up → Steady → Ramp-down 패턴

### 3단계: Locust 대시보드 활용 (10분)
- 웹 UI 접속 및 설정
- 실시간 메트릭 모니터링
- H2 DB 연동 확인

### 4단계: 베이스라인 설정 및 검증 (15분)
- TPS 2,000 목표 설정
- 응답 시간 500ms 이하 목표
- 에러율 1% 이하 목표

---

## 실습 순서

### 1단계: Locust 설치

**Python 가상 환경 생성:**
```bash
cd locust
python3 -m venv venv
source venv/bin/activate  # macOS/Linux
# 또는
# venv\Scripts\activate  # Windows
```

**Locust 설치:**
```bash
pip install --upgrade pip
pip install -r requirements.txt
```

**설치 확인:**
```bash
locust --version
```

✅ **예상 결과:** `locust 2.17.0` 이상

---

### 2단계: Locust 스크립트 구조 이해

**파일 위치:**
```
locust/
├── load_test.py          # Locust 테스트 스크립트
├── requirements.txt      # Python 의존성
└── run_locust.sh         # 실행 스크립트
```

**주요 구성 요소:**

1. **HttpUser 클래스:**
   - 부하 테스트 사용자 정의
   - `wait_time`: 요청 간 대기 시간
   - `tasks`: 실행할 작업 세트

2. **TaskSet 클래스:**
   - 시나리오별 작업 그룹
   - `@task`: 작업 가중치 설정
   - `on_start()`: 시나리오 시작 시 초기화

3. **시나리오:**
   - `ProductTaskSet`: 상품 조회 (가중치: 5)
   - `OrderTaskSet`: 주문 생성 (가중치: 3)
   - `PaymentTaskSet`: 결제 처리 (가중치: 1)

**실습:**
```bash
cat locust/load_test.py
```

---

### 3단계: 애플리케이션 실행

**애플리케이션 시작:**
```bash
./gradlew bootRun
```

**상품 데이터 준비 (H2 Console):**
```sql
INSERT INTO products (product_id, name, price, stock, created_at, updated_at) 
VALUES 
  ('product-001', '테스트 상품 1', 10000, 100, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('product-002', '테스트 상품 2', 20000, 50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('product-003', '테스트 상품 3', 30000, 30, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
```

✅ **확인:** `http://localhost:8080/api/products/product-001` 접속 시 정상 응답

---

### 4단계: Locust 실행 (웹 UI 모드)

**실행 스크립트 사용:**
```bash
cd locust
./run_locust.sh
# 또는
source venv/bin/activate
locust -f load_test.py --host=http://localhost:8080
```

**웹 UI 접속:**
1. 브라우저에서 `http://localhost:8089` 접속
2. 설정 입력:
   - **Number of users**: `100` (최대 동시 사용자 수)
   - **Spawn rate**: `10` (초당 생성할 사용자 수)
   - **Host**: `http://localhost:8080`
3. **Start swarming** 클릭

**실시간 모니터링:**
- **Statistics**: 요청별 통계 (RPS, 평균 응답 시간, 에러율)
- **Charts**: 실시간 그래프 (RPS, 응답 시간)
- **Failures**: 실패한 요청 목록
- **Exceptions**: 예외 발생 내역
- **Download Data**: CSV/HTML 리포트 다운로드

---

### 5단계: Ramp-up → Steady → Ramp-down 패턴

**Ramp-up 단계:**
- 사용자 수를 점진적으로 증가 (예: 0 → 100명, 10명/초)
- 시스템 부하를 점진적으로 증가시켜 안정성 확인

**Steady 단계:**
- 최대 사용자 수 유지 (예: 100명, 5분간)
- 일정한 부하로 시스템 안정성 및 성능 측정

**Ramp-down 단계:**
- 사용자 수를 점진적으로 감소 (예: 100 → 0명)
- 정상 종료를 위한 단계

**헤드리스 모드 실행:**
```bash
locust -f load_test.py \
  --host=http://localhost:8080 \
  --headless \
  --users=100 \
  --spawn-rate=10 \
  --run-time=5m \
  --html=report.html
```

**설명:**
- `--headless`: 웹 UI 없이 실행
- `--users=100`: 최대 100명의 동시 사용자
- `--spawn-rate=10`: 초당 10명씩 증가
- `--run-time=5m`: 5분간 실행
- `--html=report.html`: HTML 리포트 생성

---

### 6단계: 베이스라인 설정 및 검증

**목표 설정:**
- **TPS (Transactions Per Second)**: 2,000 이상
- **평균 응답 시간**: 500ms 이하
- **에러율**: 1% 이하

**실행 및 검증:**
```bash
locust -f load_test.py \
  --host=http://localhost:8080 \
  --headless \
  --users=200 \
  --spawn-rate=20 \
  --run-time=3m \
  --html=baseline_report.html
```

**결과 확인:**
1. **Statistics 탭**에서 각 엔드포인트별 RPS 확인
2. **Charts 탭**에서 응답 시간 추이 확인
3. **Failures 탭**에서 에러율 확인

**예상 결과:**
```
Type     Name                          # reqs      # fails  Avg     Min     Max     Median  req/s
GET      /api/products/[productId]     50000       0        45ms    10ms    200ms   40ms    166.67
GET      /api/products/[productId]/stock 30000     0        30ms    8ms     150ms   25ms    100.00
POST     /api/orders                   20000       50       120ms   50ms    400ms   100ms   66.67
POST     /api/payments                 5000        5        150ms   60ms    450ms   130ms   16.67
```

✅ **검증 기준:**
- 총 RPS ≥ 2,000
- 평균 응답 시간 ≤ 500ms
- 에러율 ≤ 1%

---

### 7단계: H2 DB 연동 확인

**H2 Console 접속:**
1. `http://localhost:8080/h2-console` 접속
2. 연결 정보:
   - JDBC URL: `jdbc:h2:mem:testdb`
   - Username: `sa`
   - Password: `sa`

**부하 테스트 중 DB 상태 확인:**
```sql
-- 주문 수 확인
SELECT COUNT(*) FROM orders;

-- 결제 수 확인
SELECT COUNT(*) FROM payments;

-- 상품 재고 확인
SELECT product_id, name, stock FROM products;

-- 최근 주문 조회
SELECT * FROM orders ORDER BY created_at DESC LIMIT 10;
```

**성능 메트릭 확인:**
- 트랜잭션 처리량
- 데이터 일관성
- 동시성 제어 (비관적 락) 동작 확인

---

## 핵심 메시지 4가지

### 1. Locust 기본 구조 이해

**HttpUser 클래스:**
- 부하 테스트 사용자 정의
- `wait_time`: 요청 간 대기 시간 설정
- `tasks`: 실행할 작업 세트 및 가중치

**TaskSet 클래스:**
- 시나리오별 작업 그룹화
- `@task` 데코레이터로 작업 가중치 설정
- `on_start()` / `on_stop()`으로 초기화/정리

**실전 팁:**
- 가중치를 통해 시나리오별 실행 비율 제어
- `catch_response=True`로 응답 커스텀 처리

---

### 2. Ramp-up → Steady → Ramp-down 패턴

**Ramp-up 단계:**
- 점진적 부하 증가로 시스템 안정성 확인
- 갑작스러운 부하로 인한 장애 방지

**Steady 단계:**
- 일정한 부하로 성능 측정
- 시스템 한계점 파악

**Ramp-down 단계:**
- 정상 종료를 위한 점진적 부하 감소
- 리소스 정리 및 데이터 일관성 확인

**실전 팁:**
- `--spawn-rate`로 Ramp-up 속도 제어
- `--run-time`으로 Steady 단계 지속 시간 설정

---

### 3. Locust 대시보드 활용

**실시간 모니터링:**
- **Statistics**: 요청별 상세 통계
- **Charts**: 실시간 그래프 (RPS, 응답 시간)
- **Failures**: 실패한 요청 분석
- **Exceptions**: 예외 발생 내역

**H2 DB 연동:**
- 부하 테스트 중 DB 상태 실시간 확인
- 트랜잭션 처리량 및 데이터 일관성 검증
- 동시성 제어 동작 확인

**실전 팁:**
- 웹 UI로 실시간 모니터링, 헤드리스 모드로 자동화
- HTML 리포트로 결과 문서화

---

### 4. 베이스라인 설정 및 검증

**목표 설정:**
- **TPS**: 2,000 이상
- **응답 시간**: 500ms 이하
- **에러율**: 1% 이하

**검증 방법:**
- Statistics 탭에서 RPS 확인
- Charts 탭에서 응답 시간 추이 확인
- Failures 탭에서 에러율 확인

**실전 팁:**
- 단계적으로 부하 증가하여 한계점 파악
- 다양한 시나리오 조합으로 실제 사용 패턴 반영
- 정기적인 부하 테스트로 성능 회귀 방지

---

## 프로젝트 구조

```
locust/
├── load_test.py          # Locust 테스트 스크립트
├── requirements.txt      # Python 의존성
└── run_locust.sh         # 실행 스크립트

docs/
├── issue7.md             # 실습 가이드 (이 파일)
└── issue7.puml          # 시퀀스 다이어그램
```

---

## 실습 체크리스트

- [ ] 1단계: Locust 설치
- [ ] 2단계: Locust 스크립트 구조 이해
- [ ] 3단계: 애플리케이션 실행
- [ ] 4단계: Locust 실행 (웹 UI 모드)
- [ ] 5단계: Ramp-up → Steady → Ramp-down 패턴 실행
- [ ] 6단계: 베이스라인 설정 및 검증
- [ ] 7단계: H2 DB 연동 확인

---

## 문제 해결

### Locust 설치 오류

**Python 버전 확인:**
```bash
python3 --version
# Python 3.8 이상 필요
```

**가상 환경 재생성:**
```bash
rm -rf venv
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

### 연결 오류 (Connection refused)

**애플리케이션 실행 확인:**
```bash
curl http://localhost:8080/actuator/health
```

**Host 설정 확인:**
- Locust 실행 시 `--host=http://localhost:8080` 확인
- 또는 `run_locust.sh` 스크립트의 `LOCUST_HOST` 환경 변수 확인

### 메모리 부족

**사용자 수 감소:**
```bash
locust -f load_test.py --host=http://localhost:8080 --users=50 --spawn-rate=5
```

**실행 시간 단축:**
```bash
locust -f load_test.py --host=http://localhost:8080 --run-time=2m
```

---

## 참고 자료

- **시퀀스 다이어그램:** `issue7.puml` 파일 참조
- **Locust 공식 문서:** https://docs.locust.io/
- **상세 개념 설명:** 각 단계별 파일 확인
