#!/bin/bash

# Locust 부하 테스트 실행 스크립트

echo "=== Locust 부하 테스트 실행 ==="
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

# Locust 실행 옵션
# -f: 스크립트 파일
# --host: 대상 서버 URL
# --users: 최대 동시 사용자 수 (Ramp-up)
# --spawn-rate: 초당 생성할 사용자 수
# --run-time: 실행 시간 (예: 5m = 5분)
# --headless: 웹 UI 없이 실행
# --html: HTML 리포트 생성

echo "Locust 실행 옵션:"
echo "  1. 웹 UI 모드 (기본): locust --host=$BASE_URL"
echo "  2. 헤드리스 모드: locust --host=$BASE_URL --headless --users=100 --spawn-rate=10 --run-time=5m"
echo ""

# 실행 모드 선택
read -p "실행 모드를 선택하세요 (1: 웹 UI, 2: 헤드리스, 기본: 1): " mode

if [ "$mode" == "2" ]; then
    echo "헤드리스 모드로 실행합니다..."
    locust -f load_test.py \
        --host="$BASE_URL" \
        --headless \
        --users=100 \
        --spawn-rate=10 \
        --run-time=5m \
        --html=report.html
    echo ""
    echo "✅ 리포트 생성: report.html"
else
    echo "웹 UI 모드로 실행합니다..."
    echo "브라우저에서 http://localhost:8089 접속"
    echo ""
    locust -f load_test.py --host="$BASE_URL"
fi
