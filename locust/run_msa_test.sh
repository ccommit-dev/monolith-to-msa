#!/bin/bash

# MSA 환경 성능 테스트 실행 스크립트

echo "=== MSA 환경 성능 테스트 ==="
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

# 서비스 URL 확인
ORDER_SERVICE_URL="${ORDER_SERVICE_URL:-http://localhost:8080}"
PAYMENT_SERVICE_URL="${PAYMENT_SERVICE_URL:-http://localhost:8081}"

echo "서비스 URL:"
echo "  - Order Service: $ORDER_SERVICE_URL"
echo "  - Payment Service: $PAYMENT_SERVICE_URL"
echo ""

# 서비스 상태 확인
echo "서비스 상태 확인 중..."
if curl -s "$ORDER_SERVICE_URL/actuator/health" > /dev/null; then
    echo "✅ Order Service: 정상"
else
    echo "❌ Order Service: 연결 실패"
    echo "   Order Service가 실행 중인지 확인하세요."
    exit 1
fi

if curl -s "$PAYMENT_SERVICE_URL/actuator/health" > /dev/null; then
    echo "✅ Payment Service: 정상"
else
    echo "⚠️  Payment Service: 연결 실패 (선택적)"
    echo "   Payment Service가 없어도 Order Service 테스트는 가능합니다."
fi

echo ""

# 테스트 시나리오 선택
echo "테스트 시나리오:"
echo "  1. 통합 테스트 (Order → Payment 플로우, load_test.py)"
echo "  2. 병목 재현 테스트 (load_test_bottleneck.py)"
echo ""

read -p "테스트 시나리오를 선택하세요 (1: 통합, 2: 병목 재현, 기본: 1): " scenario

if [ "$scenario" == "2" ]; then
    TEST_FILE="load_test_bottleneck.py"
    REPORT_FILE="report_msa_bottleneck_vu100.html"
    CSV_PREFIX="results_msa_bottleneck_vu100"
    echo ""
    echo "병목 재현 테스트 실행"
else
    TEST_FILE="load_test.py"
    REPORT_FILE="report_msa_integrated_vu100.html"
    CSV_PREFIX="results_msa_integrated_vu100"
    echo ""
    echo "통합 테스트 실행 (Order → Payment 플로우)"
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
echo "테스트 설정:"
echo "  - 대상: $ORDER_SERVICE_URL"
echo "  - VU: $vu_count"
echo "  - Spawn rate: $spawn_rate/초"
echo "  - 실행 시간: $run_time"
echo ""

read -p "테스트를 시작하시겠습니까? (y/n): " confirm
if [ "$confirm" != "y" ]; then
    echo "테스트 취소"
    exit 0
fi

echo ""
echo "테스트 시작..."
echo ""

locust -f "$TEST_FILE" \
    --host="$ORDER_SERVICE_URL" \
    --headless \
    --users="$vu_count" \
    --spawn-rate="$spawn_rate" \
    --run-time="$run_time" \
    --html="$REPORT_FILE" \
    --csv="$CSV_PREFIX"

echo ""
echo "✅ 테스트 완료"
echo "✅ 리포트: $REPORT_FILE"
echo "✅ CSV 결과: ${CSV_PREFIX}_*.csv"
echo ""
echo "📊 성능 비교:"
echo "   - 모놀리식 리포트: report_monolith_vu100.html (Issue7/Issue8)"
echo "   - MSA 리포트: $REPORT_FILE"
echo "   - 두 리포트를 비교하여 개선 효과를 확인하세요."

