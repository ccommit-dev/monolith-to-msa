#!/bin/bash

# Ch06.12: 성능 비교 테스트 실행 스크립트
# Before (모놀리식) vs After (MSA + 비동기 + 캐싱 + Circuit Breaker)

echo "=== Ch06.12: 성능 비교 테스트 ==="
echo ""

# Python 가상 환경 확인
if [ ! -d "venv" ]; then
    echo "Python 가상 환경이 없습니다. 생성합니다..."
    python3 -m venv venv
    source venv/bin/activate
    pip install --upgrade pip
    pip install -r requirements.txt
else
    echo "Python 가상 환경 활성화..."
    source venv/bin/activate
fi

# Locust 설치 확인
if ! command -v locust &> /dev/null; then
    echo "Locust가 설치되지 않았습니다. 설치합니다..."
    pip install -r requirements.txt
fi

echo ""
echo "성능 비교 테스트 시나리오:"
echo "  1. Before: 모놀리식 환경 테스트"
echo "  2. After: MSA + 비동기 + 캐싱 + Circuit Breaker 환경 테스트"
echo "  3. 성능 비교 리포트 생성"
echo ""

# 테스트 환경 선택
read -p "테스트를 시작하시겠습니까? (y/n): " confirm
if [ "$confirm" != "y" ]; then
    echo "테스트 취소"
    exit 0
fi

# VU 수 선택
read -p "Virtual Users 수를 입력하세요 (기본: 100): " vu_count
vu_count=${vu_count:-100}

# Spawn rate 선택
read -p "Spawn rate를 입력하세요 (기본: 10): " spawn_rate
spawn_rate=${spawn_rate:-10}

# 실행 시간 선택
read -p "실행 시간을 입력하세요 (기본: 5m): " run_time
run_time=${run_time:-5m}

echo ""
echo "=== 1단계: Before (모놀리식) 테스트 ==="
echo ""

read -p "Before 테스트를 실행하시겠습니까? (y/n): " run_before
if [ "$run_before" == "y" ]; then
    BEFORE_URL="${BEFORE_URL:-http://localhost:8080}"
    echo "대상 URL: $BEFORE_URL"
    echo ""
    
    locust -f load_test_performance_comparison.py \
        --host="$BEFORE_URL" \
        --headless \
        --users="$vu_count" \
        --spawn-rate="$spawn_rate" \
        --run-time="$run_time" \
        --html=report_before_vu${vu_count}.html \
        --csv=results_before_vu${vu_count}
    
    echo ""
    echo "✅ Before 테스트 완료"
    echo "✅ 리포트: report_before_vu${vu_count}.html"
    echo "✅ CSV 결과: results_before_vu${vu_count}_*.csv"
fi

echo ""
echo "=== 2단계: After (MSA + 개선) 테스트 ==="
echo ""

read -p "After 테스트를 실행하시겠습니까? (y/n): " run_after
if [ "$run_after" == "y" ]; then
    AFTER_URL="${AFTER_URL:-http://localhost:8080}"
    echo "대상 URL: $AFTER_URL"
    echo ""
    
    locust -f load_test_performance_comparison.py \
        --host="$AFTER_URL" \
        --headless \
        --users="$vu_count" \
        --spawn-rate="$spawn_rate" \
        --run-time="$run_time" \
        --html=report_after_vu${vu_count}.html \
        --csv=results_after_vu${vu_count}
    
    echo ""
    echo "✅ After 테스트 완료"
    echo "✅ 리포트: report_after_vu${vu_count}.html"
    echo "✅ CSV 결과: results_after_vu${vu_count}_*.csv"
fi

echo ""
echo "=== 3단계: 성능 비교 분석 ==="
echo ""

read -p "성능 비교 리포트를 생성하시겠습니까? (y/n): " run_compare
if [ "$run_compare" == "y" ]; then
    BEFORE_PREFIX="results_before_vu${vu_count}"
    AFTER_PREFIX="results_after_vu${vu_count}"
    
    # Locust --csv=PREFIX 는 PREFIX_stats.csv 파일을 현재 디렉터리에 생성함 (디렉터리가 아님)
    if [ -f "${BEFORE_PREFIX}_stats.csv" ] && [ -f "${AFTER_PREFIX}_stats.csv" ]; then
        python3 compare_performance.py "$BEFORE_PREFIX" "$AFTER_PREFIX"
    else
        echo "❌ Locust 통계 CSV를 찾을 수 없습니다."
        echo "   기대 파일: ${BEFORE_PREFIX}_stats.csv , ${AFTER_PREFIX}_stats.csv"
    fi
fi

echo ""
echo "=== 성능 비교 테스트 완료 ==="
echo ""
echo "📊 리포트 파일:"
echo "   - Before: report_before_vu${vu_count}.html"
echo "   - After:  report_after_vu${vu_count}.html"
echo "   - 비교:   performance_comparison.json"
echo ""
echo "💡 핵심 개선 사항:"
echo "   1. 독립 DB: 서비스별 독립적인 데이터베이스"
echo "   2. 비동기: Redis Pub/Sub을 통한 이벤트 기반 처리"
echo "   3. 캐싱: Redis를 통한 상품/재고 정보 캐싱"
echo "   4. Circuit Breaker: 장애 격리 및 빠른 실패"
echo ""
