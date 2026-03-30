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
$py = Join-Path $PSScriptRoot "venv\Scripts\python.exe"

Write-Host "=== Before (기본 프로필 8080 가정) — 앱 기동 후 실행 ==="
Write-Host "venv PATH 없이 실행(권장):"
Write-Host ('& "{0}" -m locust -f load_test_performance_comparison.py --host=http://localhost:8080 --headless --users={1} --spawn-rate={2} --run-time={3} --html=report_before_vu{1}.html --csv={4}' -f $py, $vu, $spawn, $runTime, $beforePrefix)
Write-Host ""
Write-Host "=== After (order 8080 + payment 8081 + Redis 가정) — 앱 기동 후 실행 ==="
Write-Host ('& "{0}" -m locust -f load_test_performance_comparison.py --host=http://localhost:8080 --headless --users={1} --spawn-rate={2} --run-time={3} --html=report_after_vu{1}.html --csv={4}' -f $py, $vu, $spawn, $runTime, $afterPrefix)
Write-Host ""
Write-Host "=== 비교 (두 CSV 생성 후) ==="
Write-Host ('& "{0}" compare_performance.py {1} {2}' -f $py, $beforePrefix, $afterPrefix)
Write-Host ""
Write-Host "대안: . .\venv\Scripts\Activate.ps1 후 python -m locust -f load_test_performance_comparison.py ..."
