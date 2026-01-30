# monolith-to-msa
패스트캠퍼스 대용량 트래픽 msa 아키텍처 실습

# 🚀 Backend Performance & AI Operations Project

이 프로젝트는 **Spring Boot 기반 백엔드 시스템을 설계 → 성능 최적화 → AI 기반 운영 자동화**까지  
실무 흐름 그대로 학습할 수 있도록 구성된 **실전 중심 커리큘럼**입니다.

- 모놀리식 → MSA 전환
- 캐시 / 비동기 / 회복탄력성 설계
- 부하 테스트 및 병목 분석
- AI 기반 트래픽 분석, 이상 탐지, 자동 대응, RCA

---

## 📦 Ch06. Backend Architecture & Performance

### Ch06.01 프로젝트 설정 (40분)
- Spring Boot 프로젝트 생성
- 필수 의존성 설정 (`build.gradle`)
- `application.yml` 환경 설정
- Docker 환경 구성
- Health Check 설정
- 프로젝트 구조 설계
- ✅ 실습 체크리스트

---

### Ch06.02 모놀리식 설계 (50분)
- 레이어드 아키텍처 (Layered Architecture)
- 컴포넌트 설계  
  `Controller → Service → Repository`
- 모듈 의존성 설계 (단방향, DIP)
- 패키지 구조 (Domain 중심)
- 실전 예시: Order 도메인 (시퀀스 다이어그램)
- 💡 핵심 메시지 (4가지)

---

### Ch06.03 도메인 모델링 (50분)
- `Order` 엔티티 설계 (`@Entity`, 필드, 메서드)
- `Payment` 엔티티 설계 및 관계 설정
- ERD 작성 (Entity Relationship Diagram)
- DDL 스크립트 작성 (CREATE TABLE, 제약조건)
- 연관관계 설정  
  `@OneToMany`, `@ManyToOne`
- 영속성 컨텍스트  
  (1차 캐시, 변경 감지, 쓰기 지연)
- 💡 핵심 메시지 (4가지)

---

### Ch06.04 주문 API (50분)
- `POST /api/orders` 구현 (Controller, DTO)
- `@Transactional` 트랜잭션 관리 (ACID)
- 재고 차감 + 주문 생성 로직 (시퀀스 다이어그램)
- `OrderService` 비즈니스 로직 구현
- 예외 처리  
  (Custom Exception, `@RestControllerAdvice`)
- 단위 테스트 (Given-When-Then)
- 💡 핵심 메시지 (4가지)

---

### Ch06.05 결제 로직 (50분)
- 결제 프로세스 (시퀀스 다이어그램)
- 상태 관리  
  `PENDING → COMPLETED / FAILED`
- `PaymentService` 구현 (`@Transactional`, PG 호출)
- 에러 처리 및 재시도  
  (Timeout, `@Retryable`, Circuit Breaker)
- 통합 테스트  
  (`@SpringBootTest`, `MockMvc`)
- 💡 핵심 메시지 (4가지)

---

### Ch06.06 Redis 캐시 (50분)
- Cache Aside 패턴 (시퀀스 다이어그램)
- `@Cacheable` 구현 (설정, 사용법)
- TTL 전략 (60초 ~ 3600초)
- 성능 비교  
  - Before: 200ms  
  - After: 10ms (20배 향상)
- 실전 예시  
  (상품 조회, 재고 조회 캐싱)
- 💡 핵심 메시지 (4가지)

---

### Ch06.07 부하 테스트 (50분)
- k6 기본 스크립트  
  (설치, 구조, 실행)
- 시나리오 작성  
  (Ramp-up → Steady → Ramp-down)
- Grafana 대시보드  
  (InfluxDB 연동, 실시간 시각화)
- 베이스라인 설정  
  - TPS: 2,000  
  - 응답 시간: 500ms  
  - 에러율: 1%
- 💡 핵심 메시지 (4가지)

---

### Ch06.08 병목 재현 (40분)
- 병목 현상 재현  
  - VU 100: 정상  
  - VU 200: 병목 발생
- 커넥션 풀 고갈  
  (`HikariCP max-pool-size = 10`)
- 분석 리포트  
  (병목 지점, 근본 원인, 해결 방안)
- 💡 핵심 메시지
  - VU 200 병목
  - 커넥션 풀 고갈
  - 모놀리식 한계
  - MSA 필요성

---

### Ch06.09 서비스 분리 (40분)
- 서비스 분리 전략  
  (Monolith → Order + Payment)
- Order Service  
  - Port: 8080  
  - DB: orderdb
- Payment Service  
  - Port: 8081  
  - DB: paymentdb
- API 통신 구조 (REST, 시퀀스 다이어그램)
- Docker 기반 독립 배포 및 확장
- 💡 핵심 메시지 (4가지)

---

### Ch06.10 통신 구조 (40분)
- WebClient 전환  
  (`RestTemplate → WebClient`)
- Non-blocking I/O
- Resilience4j  
  (Circuit Breaker, Retry, Timeout)
- Fallback 처리  
  (Payment 실패 시 보류)
- 💡 핵심 메시지 (4가지)

---

### Ch06.11 비동기 처리 (40분)
- Redis Pub/Sub 구조  
  (Publish → Subscribe)
- 이벤트 기반 아키텍처  
  (`OrderCreated → PaymentCompleted`)
- DLQ (Dead Letter Queue)
- 💡 핵심 메시지 (4가지)

---

### Ch06.12 성능 비교 (30분)
- Before vs After 성능 비교  
  - TPS ×4  
  - 응답 시간 90% 감소
- 주요 개선 사항  
  (독립 DB, 비동기, 캐싱, Circuit Breaker)
- Ch06 전체 흐름 정리
- 💡 핵심 메시지  
  - 측정 → 분석 → 개선 → 검증

---

## 🤖 Ch07. AI-Based Operations

### Ch07.01 AI 트래픽 데이터 수집 (50분)
- 핵심 메트릭  
  (TPS, Latency, Error Rate, Resource)
- 데이터 파이프라인  
  (수집 → 저장 → 처리 → 시각화)
- AI 이상 탐지  
  (정상 패턴 학습 → 이상치 감지)
- 인사이트 대시보드  
  (Grafana + AI 통찰)
- 💡 핵심 메시지 (4가지)

---

### Ch07.02 AI 이상징후 탐지 (50분)
- Baseline Learning  
  (정상 패턴 7일 학습 → 기준선)
- AI 자동 감지  
  (실시간 모니터링 → 알림)
- Part 2/3 병목 자동 감지  
  (커넥션 풀 고갈 탐지)
- False Positive 최소화  
  (30% → 5%)
- 💡 핵심 메시지 (4가지)

---

### Ch07.03 AI 자동알림 대응 (50분)
- Alert Fatigue 해결  
  (스마트 알림, 100건 → 5건)
- 자동 Incident 생성
- Auto-remediation  
  (CPU / Memory / DB Connection)
- PagerDuty / Opsgenie 연동
- 💡 핵심 메시지 (4가지)

---

### Ch07.04 AI 근본원인 분석 (50분)
- Part 6 장애 AI 자동 진단  
  (로그 → 메트릭 → 근본 원인)
- AI 추천 vs 엔지니어 판단  
  (협업 모델)
- 아키텍처 전환 효과 분석  
  (Before / After 자동 측정)
- AI 시대의 엔지니어 역할  
  (AI Supervisor, Problem Solver)
- 💡 핵심 메시지 (4가지)

---

## 🎯 Goal

> **측정 가능한 성능 개선과  
> 설명 가능한 AI 운영 시스템을 설계하는 것**

이 프로젝트는 단순 구현이 아니라  
**실무에서 바로 적용 가능한 사고방식과 구조**를 목표로 합니다.
