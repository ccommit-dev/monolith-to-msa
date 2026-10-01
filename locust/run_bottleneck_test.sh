#!/bin/bash

# Locust 병목 재현 부하 테스트 실행 스크립트

echo "=== Locust 병목 재현 부하 테스트 ==="
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

# 애플리케이션 URL 확인
BASE_URL="${LOCUST_HOST:-http://localhost:8080}"
echo "대상 URL: $BASE_URL"
echo ""

echo "병목 재현 테스트 시나리오:"
echo "  1. VU 100: 정상 동작 테스트 (베이스라인)"
echo "  2. VU 200: 병목 현상 재현 (커넥션 풀 고갈)"
echo ""

# 테스트 시나리오 선택
read -p "테스트 시나리오를 선택하세요 (1: VU 100, 2: VU 200, 기본: 1): " scenario

if [ "$scenario" == "2" ]; then
    echo ""
    echo "⚠️  VU 200 병목 재현 테스트 실행"
    echo "   - 최대 동시 사용자: 200"
    echo "   - 커넥션 풀: max 10 (고갈 예상)"
    echo "   - 실행 시간: 5분"
    echo ""
    read -p "계속하시겠습니까? (y/n): " confirm
    if [ "$confirm" != "y" ]; then
        echo "테스트 취소"
        exit 0
    fi
    
    locust -f load_test_bottleneck.py \
        --host="$BASE_URL" \
        --headless \
        --users=200 \
        --spawn-rate=20 \
        --run-time=5m \
        --html=report_bottleneck_vu200.html \
        --csv=results_bottleneck_vu200
    echo ""
    echo "✅ 리포트 생성: report_bottleneck_vu200.html"
    echo "✅ CSV 결과: results_bottleneck_vu200_*.csv"
else
    echo ""
    echo "VU 100 정상 동작 테스트 실행"
    echo "   - 최대 동시 사용자: 100"
    echo "   - 실행 시간: 5분"
    echo ""
    
    locust -f load_test_bottleneck.py \
        --host="$BASE_URL" \
        --headless \
        --users=100 \
        --spawn-rate=10 \
        --run-time=5m \
        --html=report_bottleneck_vu100.html \
        --csv=results_bottleneck_vu100
    echo ""
    echo "✅ 리포트 생성: report_bottleneck_vu100.html"
    echo "✅ CSV 결과: results_bottleneck_vu100_*.csv"
fi

echo ""
echo "📊 메트릭 확인:"
echo "   - 커넥션 풀 상태: http://localhost:8080/actuator/metrics/hikari.connections.active"
echo "   - 응답 시간: http://localhost:8080/actuator/metrics/http.server.requests"
echo "   - 에러율: Locust 리포트 확인"

