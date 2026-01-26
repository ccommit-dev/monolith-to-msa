# Ch06.01: 프로젝트 설정 실습 가이드

## 실습 목표
Spring Boot 프로젝트 기본 설정 및 환경 구성

## 프로젝트 구조
```
src/main/java/com/ccommit/monolith_to_msa/
├── MonolithToMsaApplication.java    # 메인 애플리케이션
├── config/
│   └── WebConfig.java               # Web 설정 (CORS 등)
└── controller/
    ├── HomeController.java          # 홈 컨트롤러
    └── HealthController.java        # Health Check 컨트롤러
```

## 실습 순서

### 1. 의존성 확인 및 빌드
```bash
# 의존성 다운로드 및 빌드
./gradlew clean build
```

### 2. 애플리케이션 실행
```bash
./gradlew bootRun
```

### 3. Health Check 테스트 (새 터미널)
```bash
# 커스텀 Health Check
curl http://localhost:8080/api/health

# Spring Actuator Health Check
curl http://localhost:8080/actuator/health
```

### 4. H2 Console 접속

**브라우저에서 접속:**
```
http://localhost:8080/h2-console
```

#### 메모리 DB 사용 (현재 설정 - 개발용)

**연결 정보:**
- **JDBC URL:** `jdbc:h2:mem:testdb`
- **User Name:** `sa`
- **Password:** (비워두기 - 아무것도 입력하지 않음)

**특징:**
- 애플리케이션 종료 시 데이터 삭제
- 빠른 개발 및 테스트에 적합

#### 파일 DB 사용 (데이터 영구 저장)

**1. application.yaml 수정:**
```yaml
spring:
  datasource:
    # 메모리 DB (주석 처리)
    # url: jdbc:h2:mem:testdb
    # 파일 DB (활성화)
    url: jdbc:h2:file:./data/testdb
    driver-class-name: org.h2.Driver
    username: sa
    password: yourpassword  # 원하는 비밀번호
```

**2. 애플리케이션 재시작:**
```bash
./gradlew bootRun
```

**3. H2 Console 접속:**
- **JDBC URL:** `jdbc:h2:file:./data/testdb`
- **User Name:** `sa`
- **Password:** `yourpassword` (설정한 비밀번호 입력)

**특징:**
- 데이터 영구 저장 (`./data/` 디렉토리에 저장)
- 애플리케이션 재시작 후에도 데이터 유지

#### Password 설정 방법

**방법 1: application.yaml에서 설정 (권장)**
```yaml
spring:
  datasource:
    url: jdbc:h2:file:./data/testdb
    username: sa
    password: yourpassword  # 원하는 비밀번호
```

**방법 2: JDBC URL에 Password 포함**
- **JDBC URL:** `jdbc:h2:file:./data/testdb;PASSWORD=yourpassword`
- **User Name:** `sa`
- **Password:** (비워두기)

### 5. Docker 실행 (선택사항)
```bash
# Docker 설치 확인
docker --version

# Docker Compose 실행 (최신 Docker Desktop)
docker compose up --build

# 또는 레거시
docker-compose up --build
```

## 주요 엔드포인트
- `GET /` - 홈
- `GET /api/health` - 커스텀 Health Check
- `GET /actuator/health` - Spring Actuator Health Check
- `GET /actuator/info` - 애플리케이션 정보
- `GET /actuator/metrics` - 메트릭 정보
- `GET /h2-console` - H2 Database Console

## 실습 체크리스트

실습 순서에 따른 체크리스트:

1. [x] Spring Boot 프로젝트 생성
2. [x] 필수 의존성 추가 (build.gradle) → 실습 순서 1번: 의존성 확인 및 빌드
3. [x] application.yaml 설정 → 실습 순서 4번: H2 Console 접속
4. [x] 프로젝트 구조 정리 (컨트롤러, config 등)
5. [x] Health Check 설정 → 실습 순서 3번: Health Check 테스트
6. [x] Docker 환경 구성 → 실습 순서 5번: Docker 실행

## 문제 해결

### 애플리케이션 중지
```bash
# 터미널에서 Ctrl + C
```

### 포트 충돌
```bash
# 8080 포트 사용 중인 프로세스 확인
lsof -i :8080

# 프로세스 종료
kill -9 <PID>
```

### H2 Console 404 오류
- 애플리케이션 재시작
- Spring Boot 4.x에서는 자동 등록이 안 될 수 있음
- 대안: 로그에서 SQL 확인 (`spring.jpa.show-sql: true`)

### H2 Console 오류 해결

#### "Database not found" 오류

**원인:** 파일 경로가 잘못되었거나 데이터베이스가 생성되지 않음

**해결:**
1. **메모리 DB 사용 (권장 - 개발용)**
   - JDBC URL: `jdbc:h2:mem:testdb` (정확히 입력)
   - Password: (비워두기)
   - 잘못된 경로(예: `/Users/junshock5/test`) 사용하지 않기

2. **파일 DB 사용 시**
   - JDBC URL: `jdbc:h2:file:./data/testdb`
   - 데이터베이스 파일이 자동으로 생성됨
   - `./data/` 디렉토리가 프로젝트 루트에 생성됨

#### "Wrong user name or password" 오류

**해결:**
- application.yaml의 `username`과 `password` 확인
- H2 Console에 입력한 정보와 일치하는지 확인
- Password가 설정되어 있으면 반드시 입력
- 현재 설정: `username: sa`, `password: sa` (application.yaml 확인)

#### 권장 설정

**개발 환경:**
- 메모리 DB 사용 (`jdbc:h2:mem:testdb`)
- Password 없음
- 애플리케이션 재시작 시 데이터 초기화

**테스트 환경:**
- 파일 DB 사용 (`jdbc:h2:file:./data/testdb`)
- Password 설정 권장
- 데이터 영구 저장
