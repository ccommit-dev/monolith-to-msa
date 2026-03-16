# Monolith to MSA 실습 가이드 전체 목차

## 📚 실습 개요

이 실습은 모놀리식 아키텍처에서 마이크로서비스 아키텍처(MSA)로 전환하는 과정을 단계별로 학습하는 프로그램입니다.

### 학습 목표
- Spring Boot를 활용한 모놀리식 애플리케이션 개발
- 레이어드 아키텍처 및 도메인 모델링 이해
- 트랜잭션 관리 및 동시성 제어 학습
- Redis 캐시를 통한 성능 최적화
- Locust를 이용한 부하 테스트 및 병목 분석
- MSA로의 서비스 분리 및 독립 배포

---

## 🚀 프로젝트 생성 방법

실습을 시작하기 전에 Spring Initializr를 사용하여 프로젝트를 생성하고 IntelliJ에서 여는 방법을 안내합니다.

### 방법 1: Spring Initializr 웹사이트 사용 (권장)

#### 1단계: Spring Initializr 접속

**웹사이트:** [https://start.spring.io/](https://start.spring.io/)

#### 2단계: 프로젝트 설정

**Project 설정:**
- **Project**: `Gradle - Groovy`
- **Language**: `Java`
- **Spring Boot**: `3.2.1` (안정 버전)

**Project Metadata:**
- **Group**: `com.ccommit`
- **Artifact**: `monolith-to-msa`
- **Name**: `monolith-to-msa`
- **Description**: `Monolith to MSA Migration Project`
- **Package name**: `com.ccommit.monolith_to_msa`
- **Packaging**: `Jar`
- **Java**: `17`

#### 3단계: 의존성 추가

**ADD DEPENDENCIES 클릭 후 다음 의존성 검색 및 추가:**

```
Spring Web
Spring Data JPA
H2 Database
Lombok
Spring Boot Actuator
Validation
```

**상세 설명:**
- `Spring Web`: REST API 개발
- `Spring Data JPA`: 데이터베이스 접근
- `H2 Database`: 인메모리 데이터베이스
- `Lombok`: 보일러플레이트 코드 제거
- `Spring Boot Actuator`: Health Check 및 메트릭
- `Validation`: 입력값 검증

#### 4단계: 프로젝트 다운로드

**GENERATE 버튼 클릭**
- `monolith-to-msa.zip` 파일 다운로드
- 원하는 위치에 압축 해제

```bash
# 압축 해제
unzip monolith-to-msa.zip
cd monolith-to-msa
```

---

### 방법 2: IntelliJ IDEA에서 직접 생성

#### 1단계: IntelliJ 실행

**New Project 클릭**

#### 2단계: Spring Initializr 선택

- 좌측 메뉴에서 `Spring Initializr` 선택
- **Server URL**: `https://start.spring.io` (기본값)
- **Next 클릭**

#### 3단계: 프로젝트 설정

**Project SDK:** `17` 선택

**Project Metadata:**
- **Name**: `monolith-to-msa`
- **Group**: `com.ccommit`
- **Artifact**: `monolith-to-msa`
- **Type**: `Gradle - Groovy`
- **Language**: `Java`
- **Packaging**: `Jar`
- **Java Version**: `17`
- **Version**: `0.0.1-SNAPSHOT`

**Next 클릭**

#### 4단계: 의존성 선택

**Developer Tools:**
- ✅ Lombok
- ✅ Spring Boot Actuator

**Web:**
- ✅ Spring Web

**SQL:**
- ✅ Spring Data JPA
- ✅ H2 Database

**I/O:**
- ✅ Validation

**Create 클릭**

---

### 방법 3: 기존 프로젝트를 IntelliJ로 열기

#### 1단계: IntelliJ IDEA 실행

**Open 또는 Import 클릭**

#### 2단계: 프로젝트 선택

- Spring Initializr에서 다운로드하거나 GitHub에서 클론한 `monolith-to-msa` 폴더 선택
- **Open as Project 선택**

#### 3단계: Gradle 자동 Import

- IntelliJ가 자동으로 `build.gradle` 인식
- 우측 하단에 "Load Gradle Project" 알림 표시
- **Load 클릭**
- 의존성 다운로드 대기 (1~3분)

#### 4단계: JDK 설정 확인

**File → Project Structure (⌘;)**
- **Project SDK**: `17` 확인
- **Language Level**: `17 - Sealed types, always-strict floating-point semantics` 확인

#### 5단계: Gradle 설정 확인

**IntelliJ IDEA → Settings (⌘,)**
- **Build, Execution, Deployment → Build Tools → Gradle**
- **Gradle JVM**: `Project SDK (17)` 선택

---

### 프로젝트 실행 확인

#### 터미널에서 실행

```bash
# 프로젝트 디렉토리로 이동
cd monolith-to-msa

# Gradle Wrapper 권한 부여 (Mac/Linux)
chmod +x gradlew

# 빌드 테스트
./gradlew clean build

# 애플리케이션 실행
./gradlew bootRun
```

#### IntelliJ에서 실행

**방법 1: Run 버튼 사용**
1. `MonolithToMsaApplication.java` 파일 열기
2. `main()` 메서드 왼쪽의 ▶️ 버튼 클릭
3. `Run 'MonolithToMsaApplication.main()'` 선택

**방법 2: Gradle Task 사용**
1. 우측 Gradle 탭 클릭
2. `Tasks → application → bootRun` 더블클릭

**실행 성공 확인:**
```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/
 :: Spring Boot ::                (v3.2.1)

Started MonolithToMsaApplication in 3.5 seconds
```

**브라우저에서 확인:**
```
http://localhost:8080
```

---

### 문제 해결

#### 1. JDK 17이 설치되어 있지 않은 경우

**Mac (Homebrew):**
```bash
brew install openjdk@17
```

**Windows:**
- [Oracle JDK 17 다운로드](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
- 또는 [OpenJDK 17 다운로드](https://adoptium.net/)

**설치 확인:**
```bash
java -version
# 출력: openjdk version "17.0.x"
```

#### 2. Gradle 빌드 실패

```bash
# Gradle Wrapper 재생성
gradle wrapper

# 캐시 정리
./gradlew clean --refresh-dependencies
```

#### 3. IntelliJ에서 의존성 인식 안 됨

**File → Invalidate Caches / Restart**
- **Invalidate and Restart 클릭**

#### 4. 포트 8080이 이미 사용 중

**application.yaml에서 포트 변경:**
```yaml
server:
  port: 8081
```

---

### ✅ 프로젝트 생성 체크리스트

- [ ] Spring Initializr에서 프로젝트 생성 또는 IntelliJ에서 직접 생성
- [ ] 프로젝트를 IntelliJ로 열기
- [ ] Gradle 의존성 자동 다운로드 완료
- [ ] JDK 17 설정 확인
- [ ] `./gradlew clean build` 성공
- [ ] `./gradlew bootRun` 실행 성공
- [ ] `http://localhost:8080` 접속 확인

---

## 🗂️ 실습 단계별 목차

### Ch06.01: 프로젝트 설정 (Issue1)
- **목표**: Spring Boot 프로젝트 기본 환경 구성
- **소요 시간**: 약 30분
- **주요 내용**:
    - Gradle 프로젝트 설정
    - Spring Boot 의존성 구성
    - H2 Database 설정
    - Health Check 구현
    - Docker 환경 구성

[📄 상세 가이드: PRACTICE_GUIDE_ISSUE1.md](./PRACTICE_GUIDE_ISSUE1.md)

---

### Ch06.02: 모놀리식 설계 (Issue2)
- **목표**: 레이어드 아키텍처 기반 모놀리식 애플리케이션 구현
- **소요 시간**: 약 60분
- **주요 내용**:
    - Domain Layer (Entity, Enum)
    - Repository Layer (JPA Repository)
    - Service Layer (비즈니스 로직)
    - Controller Layer (REST API)
    - DTO 설계

[📄 상세 가이드: PRACTICE_GUIDE_ISSUE2.md](./PRACTICE_GUIDE_ISSUE2.md)

---

### Ch06.03: 도메인 모델링 (Issue3)
- **목표**: JPA 엔티티 관계 설정 및 영속성 컨텍스트 이해
- **소요 시간**: 약 50분
- **주요 내용**:
    - Order-Payment 관계 설정 (@OneToMany, @ManyToOne)
    - ERD 설계
    - DDL 스크립트
    - 영속성 컨텍스트 (1차 캐시, 변경 감지, 쓰기 지연)

[📄 상세 가이드: PRACTICE_GUIDE_ISSUE3.md](./PRACTICE_GUIDE_ISSUE3.md)

---

### Ch06.04: 주문 API (Issue4)
- **목표**: 재고 차감 + 주문 생성 로직 및 트랜잭션 관리
- **소요 시간**: 약 60분
- **주요 내용**:
    - Product 엔티티 및 재고 관리
    - 비관적 락 (Pessimistic Lock)
    - 트랜잭션 관리 (@Transactional)
    - Custom Exception 및 GlobalExceptionHandler
    - 단위 테스트 (Mockito)

[📄 상세 가이드: PRACTICE_GUIDE_ISSUE4.md](./PRACTICE_GUIDE_ISSUE4.md)

---

### Ch06.05: 결제 로직 (Issue5)
- **목표**: 결제 상태 관리 및 재시도 로직 구현
- **소요 시간**: 약 60분
- **주요 내용**:
    - Payment 상태 관리 (State Machine)
    - PaymentGatewayService (Mock)
    - 재시도 로직 (@Retryable, @Backoff)
    - 통합 테스트 (@SpringBootTest)

[📄 상세 가이드: PRACTICE_GUIDE_ISSUE5.md](./PRACTICE_GUIDE_ISSUE5.md)

---

### Ch06.06: Redis 캐시 (Issue6)
- **목표**: Cache Aside 패턴 및 성능 최적화
- **소요 시간**: 약 50분
- **주요 내용**:
    - Redis 설정
    - @Cacheable 구현
    - TTL 전략 (상품: 60초, 재고: 300초)
    - 성능 비교 (Before: 200ms → After: 10ms)

[📄 상세 가이드: PRACTICE_GUIDE_ISSUE6.md](./PRACTICE_GUIDE_ISSUE6.md)

---

### Ch06.07: Locust 부하 테스트 (Issue7)
- **목표**: 부하 테스트 및 실시간 모니터링
- **소요 시간**: 약 50분
- **주요 내용**:
    - Locust 설치 및 설정
    - 시나리오 작성 (상품 조회, 주문 생성, 결제 처리)
    - Ramp-up → Steady → Ramp-down 패턴
    - 베이스라인 설정 (TPS 2,000, 응답 500ms)

[📄 상세 가이드: PRACTICE_GUIDE_ISSUE7.md](./PRACTICE_GUIDE_ISSUE7.md)

---

### Ch06.08: 병목 재현 (Issue8)
- **목표**: 커넥션 풀 고갈 및 모놀리식 한계 분석
- **소요 시간**: 약 40분
- **주요 내용**:
    - VU 100 vs VU 200 성능 비교
    - HikariCP 커넥션 풀 고갈 재현
    - 병목 지점 분석
    - MSA 전환 필요성 인식

[📄 상세 가이드: PRACTICE_GUIDE_ISSUE8.md](./PRACTICE_GUIDE_ISSUE8.md)

---

### Ch06.09: 서비스 분리 (Issue9)
- **목표**: Order Service와 Payment Service 분리
- **소요 시간**: 약 90분
- **주요 내용**:
    - 서비스 분리 전략
    - Database per Service 패턴
    - REST API 통신 구현
    - Docker Compose 기반 독립 배포

[📄 상세 가이드: PRACTICE_GUIDE_ISSUE9.md](./PRACTICE_GUIDE_ISSUE9.md)

---

## 📋 실습 전 준비사항

### 필수 환경
- **JDK**: 17 이상
- **Gradle**: 8.5 이상
- **IDE**: IntelliJ IDEA 또는 VS Code
- **Docker**: Docker Desktop (최신 버전)
- **Python**: 3.8 이상 (Locust 부하 테스트용)
- **Git**: 최신 버전

### 선택 환경
- **Redis**: Docker 또는 로컬 설치
- **Postman**: API 테스트용
- **H2 Console**: 브라우저 기반 DB 접속

---

## 🎯 실습 진행 방법

### 1단계: 코드 클론
```bash
# 저장소 클론
git clone https://github.com/ccommit-dev/monolith-to-msa.git
cd monolith-to-msa

# 원하는 브랜치로 체크아웃
git checkout issue1  # 또는 issue2, issue3 등
```

### 2단계: 단계별 실습
각 issue별로 브랜치를 체크아웃하여 실습을 진행합니다:
- `issue1`: 프로젝트 설정
- `issue2`: 모놀리식 설계
- `issue3`: 도메인 모델링
- ... (issue9까지)

### 3단계: 빌드 및 실행
```bash
# 빌드
./gradlew clean build

# 실행
./gradlew bootRun
```

### 4단계: 테스트
```bash
# 단위 테스트
./gradlew test

# 특정 테스트만 실행
./gradlew test --tests OrderServiceTest
```

---

## 💡 학습 팁

### 코드 작성 순서
각 단계별로 다음 순서로 코드를 작성하는 것을 권장합니다:
1. **Domain Layer**: Entity, Enum
2. **Repository Layer**: JPA Repository 인터페이스
3. **Service Layer**: Service 인터페이스 → 구현체
4. **DTO Layer**: Request, Response DTO
5. **Controller Layer**: REST API Controller
6. **Exception Layer**: Custom Exception, GlobalExceptionHandler
7. **Test Layer**: 단위 테스트, 통합 테스트

### 디버깅 팁
- **로그 활성화**: `application.yaml`에서 SQL 로그 확인
- **H2 Console**: 브라우저에서 DB 상태 실시간 확인
- **Actuator**: Health Check 및 메트릭 모니터링
- **단위 테스트**: 각 계층별로 독립적으로 테스트

### 문제 해결
각 issue별 가이드 문서의 "문제 해결" 섹션을 참고하세요.

---

## 📖 참고 자료

### 공식 문서
- [Spring Boot 공식 문서](https://docs.spring.io/spring-boot/docs/current/reference/html/)
- [Spring Data JPA 공식 문서](https://docs.spring.io/spring-data/jpa/docs/current/reference/html/)
- [Locust 공식 문서](https://docs.locust.io/)
- [Docker 공식 문서](https://docs.docker.com/)

### GitHub 저장소
- [monolith-to-msa 저장소](https://github.com/ccommit-dev/monolith-to-msa)

---

## 🔍 실습 체크리스트

전체 실습 완료 여부를 확인하세요:

- [ ] Ch06.01: 프로젝트 설정
- [ ] Ch06.02: 모놀리식 설계
- [ ] Ch06.03: 도메인 모델링
- [ ] Ch06.04: 주문 API
- [ ] Ch06.05: 결제 로직
- [ ] Ch06.06: Redis 캐시
- [ ] Ch06.07: Locust 부하 테스트
- [ ] Ch06.08: 병목 재현
- [ ] Ch06.09: 서비스 분리

---

## 📧 문의 및 피드백

실습 중 문제가 발생하거나 질문이 있으시면 GitHub Issues를 통해 문의해주세요.

---

**Happy Learning! 🚀**