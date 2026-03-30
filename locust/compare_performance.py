#!/usr/bin/env python3
"""
Ch06.12: 성능 비교 리포트 분석 스크립트
- Before (모놀리식) vs After (MSA + 비동기 + 캐싱 + Circuit Breaker)
- TPS, 응답 시간, 에러율 비교
- 개선 효과 계산 및 리포트 생성
"""

import csv
import json
import sys
from pathlib import Path
from typing import Dict, List, Optional, Set
from dataclasses import dataclass


def _csv_int(row: Dict[str, str], key: str, default: int = 0) -> int:
    """Locust CSV 셀은 숫자 문자열; 빈 값·BOM 키 대비."""
    val = row.get(key)
    if val is None or val == "":
        return default
    try:
        return int(float(val))
    except ValueError:
        return default


def _failure_rate_percent(row: Dict[str, str]) -> float:
    """
    Locust *_stats.csv 는 보통 Failure Rate 컬럼이 없고 Request/Failure Count 만 있다.
    명시 컬럼이 있으면 사용하고, 없으면 실패 수 / 요청 수 * 100.
    """
    raw = row.get("Failure Rate")
    if raw is not None and str(raw).strip() != "":
        try:
            return float(raw)
        except ValueError:
            pass
    req = _csv_int(row, "Request Count", 0)
    fail = _csv_int(row, "Failure Count", 0)
    if req <= 0:
        return 0.0
    return 100.0 * fail / req


@dataclass
class PerformanceMetrics:
    """성능 지표 데이터 클래스"""
    total_requests: int
    total_failures: int
    failure_rate: float
    avg_response_time: float
    median_response_time: float
    min_response_time: float
    max_response_time: float
    p95_response_time: float
    p99_response_time: float
    requests_per_second: float
    total_time: float


@dataclass
class EndpointMetrics:
    """엔드포인트별 성능 지표"""
    endpoint: str
    requests: int
    failures: int
    failure_rate: float
    avg_response_time: float
    p95_response_time: float
    p99_response_time: float
    requests_per_second: float


def _locust_csv_search_bases() -> List[Path]:
    """Locust CSV 검색 기준: cwd 우선, 스크립트가 있는 디렉터리(보통 locust/) 폴백."""
    seen: Set[str] = set()
    out: List[Path] = []
    for b in (Path.cwd(), Path(__file__).resolve().parent):
        r = b.resolve()
        key = str(r)
        if key not in seen:
            seen.add(key)
            out.append(r)
    return out


def _try_stats_file(p: Path) -> Optional[Path]:
    if not p.is_file():
        return None
    if p.name.endswith("_stats.csv") or p.name == "stats.csv":
        return p.resolve()
    return None


def resolve_locust_stats_csv(path_or_prefix: str) -> Optional[Path]:
    """
    Locust --csv=NAME 은 보통 NAME_stats.csv 를 생성한다 (실행 시 cwd에 저장되는 경우가 많음).
    인자: 접두사(예: results_before_vu100), *_stats.csv 경로, stats.csv가 있는 디렉터리.
    cwd와 스크립트 디렉터리 양쪽에서 접두사 파일을 찾는다.
    """
    raw = Path(path_or_prefix).expanduser()

    # 1) 경로로 직접 지정 (절대 또는 cwd 기준 상대)
    hit = _try_stats_file(raw)
    if hit:
        return hit
    if raw.is_dir():
        direct = raw / "stats.csv"
        if direct.is_file():
            return direct.resolve()
        return None

    bases = _locust_csv_search_bases()
    tried_suffix = f"{path_or_prefix}_stats.csv"

    # 2) 각 기준 디렉터리에서 상대 경로 그대로의 파일 (예: sub/results_before_vu100_stats.csv)
    for base in bases:
        hit = _try_stats_file((base / path_or_prefix).resolve())
        if hit:
            return hit

    # 3) 접두사 → {prefix}_stats.csv (Locust 기본 출력명)
    for base in bases:
        hit = _try_stats_file((base / tried_suffix).resolve())
        if hit:
            return hit

    return None


def _resolve_failure_hint(path_or_prefix: str) -> str:
    lines = [
        "  다음 파일이 필요합니다 (Locust: --csv=<접두사> → <접두사>_stats.csv).",
        "  아래 경로를 확인했으나 없습니다:",
    ]
    for base in _locust_csv_search_bases():
        lines.append(f"    - {base / f'{path_or_prefix}_stats.csv'}")
    lines.append("  locust 를 locust 폴더에서 실행했는지, 또는 전체 경로로 *_stats.csv 를 넘겼는지 확인하세요.")
    return "\n".join(lines)


class PerformanceComparator:
    """성능 비교 분석기"""
    
    def __init__(self, before_csv_arg: str, after_csv_arg: str):
        self.before_csv_arg = before_csv_arg
        self.after_csv_arg = after_csv_arg
    
    def parse_csv(self, csv_file: Path) -> Dict[str, EndpointMetrics]:
        """CSV 파일 파싱"""
        metrics = {}
        
        try:
            with open(csv_file, 'r', encoding='utf-8-sig') as f:
                reader = csv.DictReader(f)
                for row in reader:
                    endpoint = row.get('Name', '')
                    if endpoint and endpoint != 'Aggregated':
                        metrics[endpoint] = EndpointMetrics(
                            endpoint=endpoint,
                            requests=_csv_int(row, 'Request Count', 0),
                            failures=_csv_int(row, 'Failure Count', 0),
                            failure_rate=_failure_rate_percent(row),
                            avg_response_time=float(row.get('Average Response Time', 0) or 0),
                            p95_response_time=float(row.get('95%', 0) or 0),
                            p99_response_time=float(row.get('99%', 0) or 0),
                            requests_per_second=float(row.get('Requests/s', 0) or 0)
                        )
        except Exception as e:
            print(f"CSV 파싱 오류: {csv_file}, {e}")
        
        return metrics
    
    def get_aggregated_metrics(self, path_or_prefix: str) -> Optional[PerformanceMetrics]:
        """Aggregated 메트릭 추출 (Locust stats CSV)"""
        stats_file = resolve_locust_stats_csv(path_or_prefix)
        
        if not stats_file:
            print(f"통계 파일을 찾을 수 없습니다: {path_or_prefix}")
            print(_resolve_failure_hint(path_or_prefix))
            return None
        
        try:
            with open(stats_file, 'r', encoding='utf-8-sig') as f:
                reader = csv.DictReader(f)
                for row in reader:
                    if row.get('Name') == 'Aggregated':
                        tr = _csv_int(row, 'Request Count', 0)
                        tf = _csv_int(row, 'Failure Count', 0)
                        return PerformanceMetrics(
                            total_requests=tr,
                            total_failures=tf,
                            failure_rate=_failure_rate_percent(row),
                            avg_response_time=float(row.get('Average Response Time', 0) or 0),
                            median_response_time=float(row.get('Median Response Time', 0) or 0),
                            min_response_time=float(row.get('Min Response Time', 0) or 0),
                            max_response_time=float(row.get('Max Response Time', 0) or 0),
                            p95_response_time=float(row.get('95%', 0) or 0),
                            p99_response_time=float(row.get('99%', 0) or 0),
                            requests_per_second=float(row.get('Requests/s', 0) or 0),
                            total_time=float(row.get('Total Request Time', 0) or 0)
                        )
        except Exception as e:
            print(f"Aggregated 메트릭 추출 오류: {e}")
        
        return None
    
    def compare(self) -> Dict:
        """성능 비교 분석"""
        before_metrics = self.get_aggregated_metrics(self.before_csv_arg)
        after_metrics = self.get_aggregated_metrics(self.after_csv_arg)
        
        if not before_metrics or not after_metrics:
            print("경고: Before 또는 After 메트릭을 찾을 수 없습니다.")
            return {}
        
        # 개선율 계산
        tps_improvement = ((after_metrics.requests_per_second - before_metrics.requests_per_second) 
                          / before_metrics.requests_per_second * 100) if before_metrics.requests_per_second > 0 else 0
        
        response_time_improvement = ((before_metrics.avg_response_time - after_metrics.avg_response_time) 
                                    / before_metrics.avg_response_time * 100) if before_metrics.avg_response_time > 0 else 0
        
        failure_rate_improvement = ((before_metrics.failure_rate - after_metrics.failure_rate) 
                                   / before_metrics.failure_rate * 100) if before_metrics.failure_rate > 0 else 0
        
        comparison = {
            'before': {
                'tps': before_metrics.requests_per_second,
                'avg_response_time': before_metrics.avg_response_time,
                'p95_response_time': before_metrics.p95_response_time,
                'p99_response_time': before_metrics.p99_response_time,
                'failure_rate': before_metrics.failure_rate,
                'total_requests': before_metrics.total_requests,
                'total_failures': before_metrics.total_failures
            },
            'after': {
                'tps': after_metrics.requests_per_second,
                'avg_response_time': after_metrics.avg_response_time,
                'p95_response_time': after_metrics.p95_response_time,
                'p99_response_time': after_metrics.p99_response_time,
                'failure_rate': after_metrics.failure_rate,
                'total_requests': after_metrics.total_requests,
                'total_failures': after_metrics.total_failures
            },
            'improvements': {
                'tps_improvement': tps_improvement,
                'tps_multiplier': after_metrics.requests_per_second / before_metrics.requests_per_second if before_metrics.requests_per_second > 0 else 0,
                'response_time_improvement': response_time_improvement,
                'response_time_reduction': response_time_improvement,
                'failure_rate_improvement': failure_rate_improvement
            }
        }
        
        return comparison
    
    def generate_report(self, comparison: Dict, output_file: str = "performance_comparison.json"):
        """비교 리포트 생성"""
        output_path = Path(output_file)
        
        with open(output_path, 'w', encoding='utf-8') as f:
            json.dump(comparison, f, indent=2, ensure_ascii=False)
        
        print(f"\n✅ 성능 비교 리포트 생성: {output_path}")
        print("\n=== 성능 비교 결과 ===")
        print(f"\n📊 TPS (Transactions Per Second):")
        print(f"  Before: {comparison['before']['tps']:.2f} req/s")
        print(f"  After:  {comparison['after']['tps']:.2f} req/s")
        print(f"  개선:   {comparison['improvements']['tps_improvement']:.1f}% ({comparison['improvements']['tps_multiplier']:.2f}배)")
        
        print(f"\n⏱️  평균 응답 시간:")
        print(f"  Before: {comparison['before']['avg_response_time']:.2f} ms")
        print(f"  After:  {comparison['after']['avg_response_time']:.2f} ms")
        print(f"  개선:   {comparison['improvements']['response_time_improvement']:.1f}% 감소")
        
        print(f"\n📈 95% 응답 시간:")
        print(f"  Before: {comparison['before']['p95_response_time']:.2f} ms")
        print(f"  After:  {comparison['after']['p95_response_time']:.2f} ms")
        
        print(f"\n❌ 실패율:")
        print(f"  Before: {comparison['before']['failure_rate']:.2f}%")
        print(f"  After:  {comparison['after']['failure_rate']:.2f}%")
        print(f"  개선:   {comparison['improvements']['failure_rate_improvement']:.1f}% 감소")
        
        print(f"\n📝 총 요청 수:")
        print(f"  Before: {comparison['before']['total_requests']:,}")
        print(f"  After:  {comparison['after']['total_requests']:,}")


def _configure_stdio_utf8() -> None:
    """Windows 기본 인코딩(cp949)에서 이모지 출력 시 UnicodeEncodeError 방지."""
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8", errors="replace")
            except (OSError, ValueError, AttributeError):
                pass


def main():
    """메인 함수"""
    _configure_stdio_utf8()
    if len(sys.argv) < 3:
        print("사용법: python compare_performance.py <before_prefix> <after_prefix> [output.json]")
        print("예시: python compare_performance.py results_before_vu100 results_after_vu100")
        print("")
        print("전제: 접두사에 대응하는 Locust 통계 파일이 이미 있어야 합니다.")
        print("  예) results_before_vu100 → results_before_vu100_stats.csv (locust --csv=... 로 생성)")
        print("  이 스크립트는 CSV를 만들지 않습니다. 먼저 headless Locust로 Before/After 부하를 각각 실행하세요.")
        sys.exit(1)
    
    before_arg = sys.argv[1]
    after_arg = sys.argv[2]
    out_json = sys.argv[3] if len(sys.argv) > 3 else "performance_comparison.json"
    
    comparator = PerformanceComparator(before_arg, after_arg)
    comparison = comparator.compare()
    
    if comparison:
        comparator.generate_report(comparison, out_json)
    else:
        print("❌ 성능 비교 실패: 메트릭을 찾을 수 없습니다.")


if __name__ == "__main__":
    main()
