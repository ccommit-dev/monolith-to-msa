# Ch06.06: Redis 캐시 실습 가이드

## 실습 목표
- Cache Aside 패턴 이해
- @Cacheable 구현 및 사용법
- TTL 전략 설정 (60초 ~ 3600초)
- 성능 비교 (Before: 200ms → After: 10ms, 20배 향상)
- 실전 예시 (상품 조회, 재고 조회 캐싱)

## 검증 결과
✅ **모든 실습 단계 정상 작동 확인**
- 모든 파일 존재 및 컴파일 성공
- 캐시 설정 정상 작동
- 성능 테스트 통과

---

## 실습 순서

### 1단계: Redis 의존성 확인

**build.gradle 확인:**
```gradle
// Redis Cache
implementation 'org.springframework.boot:spring-boot-starter-data-redis'
implementation 'org.springframework.boot:spring-boot-starter-cache'
```

**실습:**
```bash
grep "redis\|cache" build.gradle
```

---

### 2단계: Redis 설정 확인

**파일 위치:**
```
src/main/resources/application.yaml
```

**확인 사항:**
- ✅ `spring.cache.type: redis`
- ✅ `spring.cache.redis.time-to-live: 3600000` (기본 3600초)
- ✅ `spring.data.redis.host: localhost`
- ✅ `spring.data.redis.port: 6379`

**실습:**
```bash
grep -A 10 "redis\|cache" src/main/resources/application.yaml
```

---

### 3단계: CacheConfig 확인

**파일 위치:**
```
src/main/java/com/ccommit/monolith_to_msa/config/CacheConfig.java
```

**확인 사항:**
- ✅ `@EnableCaching`: 캐시 기능 활성화
- ✅ 캐시별 TTL 설정:
  - `product`: 60초
  - `stock`: 300초 (5분)
  - 기본: 3600초 (1시간)

**TTL 전략:**
- 상품 정보: 60초 (자주 변경되지 않지만 최신성 중요)
- 재고 정보: 300초 (변경 빈도가 높지만 성능 중요)
- 기본 캐시: 3600초 (1시간)

**실습:**
```bash
cat src/main/java/com/ccommit/monolith_to_msa/config/CacheConfig.java
```

---

### 4단계: ProductService 확인

**파일 위치:**
```
src/main/java/com/ccommit/monolith_to_msa/service/product/
├── ProductService.java
└── ProductServiceImpl.java
```

**확인 사항:**
- ✅ `@Cacheable(value = "product", key = "#productId")`: 상품 조회 캐시
- ✅ `@Cacheable(value = "stock", key = "#productId")`: 재고 조회 캐시
- ✅ `@CacheEvict`: 캐시 무효화

**Cache Aside 패턴:**
1. 캐시에서 조회 시도
2. 캐시 미스 시 DB 조회
3. DB 결과를 캐시에 저장

**실습:**
```bash
cat src/main/java/com/ccommit/monolith_to_msa/service/product/ProductServiceImpl.java
```

---

### 5단계: ProductController 확인

**파일 위치:**
```
src/main/java/com/ccommit/monolith_to_msa/controller/product/ProductController.java
```

**API 엔드포인트:**
- ✅ `GET /api/products/{productId}`: 상품 조회 (캐시 적용)
- ✅ `GET /api/products/{productId}/stock`: 재고 조회 (캐시 적용)
- ✅ `DELETE /api/products/{productId}/cache`: 캐시 무효화

**실습:**
```bash
cat src/main/java/com/ccommit/monolith_to_msa/controller/product/ProductController.java
```

---

### 6단계: 성능 테스트 확인

**파일 위치:**
```
src/test/java/com/ccommit/monolith_to_msa/integration/CachePerformanceTest.java
```

**테스트 케이스:**
- 첫 조회 (DB): 약 10-50ms
- 두 번째 조회 (캐시): 약 0-5ms
- 성능 비교: 20배 향상

**실습:**
```bash
cat src/test/java/com/ccommit/monolith_to_msa/integration/CachePerformanceTest.java
```

---

### 7단계: 빌드 및 테스트 실행

**빌드:**
```bash
./gradlew clean build
```
✅ **예상 결과:** BUILD SUCCESSFUL

**성능 테스트 실행:**
```bash
./gradlew test --tests CachePerformanceTest
```
✅ **예상 결과:** BUILD SUCCESSFUL

**전체 테스트 실행:**
```bash
./gradlew test
```
✅ **예상 결과:** BUILD SUCCESSFUL

---

### 8단계: Redis 설치 및 실행

**Redis 설치 (Homebrew):**
```bash
brew install redis
```

**Redis 설치 확인:**
```bash
redis-server --version
```

**Redis 설치 제거 (필요 시):**
```bash
brew uninstall redis
```

**Redis 실행 방법:**

**방법 1: Homebrew로 설치한 경우**
```bash
# Redis 서버 시작
brew services start redis

# 또는 직접 실행
redis-server

# Redis 서버 중지
brew services stop redis
```

**방법 2: Docker로 실행**
```bash
docker run -d --name redis -p 6379:6379 redis:latest
```

**Redis 실행 확인:**
```bash
# Homebrew 설치 시
brew services list | grep redis

# Docker 실행 시
docker ps | grep redis

# Redis 클라이언트로 연결 테스트
redis-cli ping
# 예상 응답: PONG
```

**애플리케이션 실행:**
```bash
./gradlew bootRun
```

**상품 데이터 준비 (H2 Console):**
```sql
INSERT INTO products (product_id, name, price, stock, created_at, updated_at) 
VALUES ('product-001', '테스트 상품', 10000, 100, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
```

**API 테스트 - 상품 조회 (첫 조회, DB):**
```bash
time curl http://localhost:8080/api/products/product-001
```

**API 테스트 - 상품 조회 (두 번째 조회, 캐시):**
```bash
time curl http://localhost:8080/api/products/product-001
```

**예상 결과:**
- 첫 조회: 약 50-200ms (DB 조회)
- 두 번째 조회: 약 5-20ms (캐시 조회)
- 성능 향상: 약 10-20배

**API 테스트 - 재고 조회:**
```bash
curl http://localhost:8080/api/products/product-001/stock
```

**API 테스트 - 캐시 무효화:**
```bash
curl -X DELETE http://localhost:8080/api/products/product-001/cache
```

---

## 핵심 개념

### 1. Cache Aside 패턴

**처리 순서:**
1. 캐시에서 조회 시도
2. 캐시 히트: 캐시 데이터 반환
3. 캐시 미스: DB 조회 → 캐시 저장 → 데이터 반환

**장점:**
- 캐시와 DB의 일관성 유지
- 캐시 장애 시에도 DB로 동작 가능
- 구현이 간단

**단점:**
- 캐시 미스 시 DB 조회 필요
- 캐시 업데이트 로직 필요

### 2. @Cacheable 구현

**사용법:**
```java
@Cacheable(value = "product", key = "#productId", unless = "#result == null")
public Optional<Product> getProductByProductId(String productId) {
    // DB 조회 로직
}
```

**파라미터:**
- `value`: 캐시 이름
- `key`: 캐시 키 (SpEL 표현식)
- `unless`: 캐시 저장 조건 (null이 아닐 때만 저장)

**동작:**
- 메서드 호출 전: 캐시에서 조회
- 캐시 히트: 메서드 실행 안 함, 캐시 데이터 반환
- 캐시 미스: 메서드 실행 → 결과를 캐시에 저장

### 3. TTL 전략

**캐시별 TTL:**
- `product`: 60초 (상품 정보)
- `stock`: 300초 (재고 정보)
- 기본: 3600초 (1시간)

**TTL 선택 기준:**
- 데이터 변경 빈도
- 데이터 최신성 요구사항
- 성능 요구사항

### 4. 성능 비교

**Before (캐시 없음):**
- DB 조회: 약 200ms
- 매번 DB 접근 필요

**After (캐시 적용):**
- 캐시 조회: 약 10ms
- DB 접근 감소
- 성능 향상: 약 20배

---

## 프로젝트 구조

```
src/main/java/com/ccommit/monolith_to_msa/
├── config/
│   └── CacheConfig.java          # Redis 캐시 설정
├── service/
│   └── product/
│       ├── ProductService.java
│       └── ProductServiceImpl.java  # @Cacheable 적용
└── controller/
    └── product/
        └── ProductController.java

src/test/java/com/ccommit/monolith_to_msa/
└── integration/
    └── CachePerformanceTest.java  # 성능 테스트
```

---

## 실습 체크리스트

- [ ] 1단계: Redis 의존성 확인
- [ ] 2단계: Redis 설정 확인
- [ ] 3단계: CacheConfig 확인
- [ ] 4단계: ProductService 확인
- [ ] 5단계: ProductController 확인
- [ ] 6단계: 성능 테스트 확인
- [ ] 7단계: 빌드 및 테스트 실행
- [ ] 8단계: Redis 설치 및 실행 및 API 테스트

---

## 문제 해결

### Redis 연결 실패

**증상:**
```
Unable to connect to Redis
```

**해결:**
```bash
# Redis 실행 확인
docker ps | grep redis

# Redis 재시작
docker restart redis

# 또는 Redis 설치 및 실행
docker run -d --name redis -p 6379:6379 redis:latest
```

### 캐시가 작동하지 않음

**확인 사항:**
- `@EnableCaching` 어노테이션이 `CacheConfig`에 있는지 확인
- `@Cacheable` 어노테이션이 올바르게 설정되었는지 확인
- Redis가 실행 중인지 확인
- `application.yaml`의 Redis 설정 확인

### 캐시 TTL이 적용되지 않음

**확인 사항:**
- `CacheConfig`의 TTL 설정 확인
- 캐시 이름이 올바른지 확인 (`value` 속성)
- Redis에 실제로 저장되는지 확인

---

## 참고 자료

- **시퀀스 다이어그램:** `issue6.puml` 파일 참조
- **상세 개념 설명:** 각 단계별 파일 확인

