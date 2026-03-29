# Ch06.12: 성능 비교 (Windows) — Locust + compare_performance.py
# 사용 전: Redis 기동, Before/After 앱은 수동으로 기동·중지하며 단계별로 실행하는 것을 권장.

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not (Test-Path "venv\Scripts\Activate.ps1")) {
    Write-Host "venv 없음. 생성: python -m venv venv && .\venv\Scripts\Activate.ps1 && pip install -r requirements.txt"
    exit 1
}

. .\venv\Scripts\Activate.ps1

$vu = if ($env:VU_COUNT) { $env:VU_COUNT } else { "100" }
$spawn = if ($env:SPAWN_RATE) { $env:SPAWN_RATE } else { "10" }
$runTime = if ($env:RUN_TIME) { $env:RUN_TIME } else { "5m" }

$beforePrefix = "results_before_vu$vu"
$afterPrefix = "results_after_vu$vu"

Write-Host "=== Before (기본 프로필 8080 가정) — 앱 기동 후 실행 ===" 
Write-Host "locust -f load_test_performance_comparison.py --host=http://localhost:8080 --headless --users=$vu --spawn-rate=$spawn --run-time=$runTime --html=report_before_vu$vu.html --csv=$beforePrefix"
Write-Host ""
Write-Host "=== After (order 8080 + payment 8081 + Redis 가정) — 앱 기동 후 실행 ==="
Write-Host "locust -f load_test_performance_comparison.py --host=http://localhost:8080 --headless --users=$vu --spawn-rate=$spawn --run-time=$runTime --html=report_after_vu$vu.html --csv=$afterPrefix"
Write-Host ""
Write-Host "=== 비교 (두 CSV 생성 후) ==="
Write-Host "python compare_performance.py $beforePrefix $afterPrefix"
