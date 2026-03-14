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
import os
from pathlib import Path
from typing import Dict, List, Optional
from dataclasses import dataclass


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


class PerformanceComparator:
    """성능 비교 분석기"""
    
    def __init__(self, before_csv_dir: str, after_csv_dir: str):
        self.before_csv_dir = Path(before_csv_dir)
        self.after_csv_dir = Path(after_csv_dir)
    
    def parse_csv(self, csv_file: Path) -> Dict[str, EndpointMetrics]:
        """CSV 파일 파싱"""
        metrics = {}
        
        try:
            with open(csv_file, 'r', encoding='utf-8') as f:
                reader = csv.DictReader(f)
                for row in reader:
                    endpoint = row.get('Name', '')
                    if endpoint and endpoint != 'Aggregated':
                        metrics[endpoint] = EndpointMetrics(
                            endpoint=endpoint,
                            requests=int(row.get('Request Count', 0)),
                            failures=int(row.get('Failure Count', 0)),
                            failure_rate=float(row.get('Failure Rate', 0)),
                            avg_response_time=float(row.get('Average Response Time', 0)),
                            p95_response_time=float(row.get('95%', 0)),
                            p99_response_time=float(row.get('99%', 0)),
                            requests_per_second=float(row.get('Requests/s', 0))
                        )
        except Exception as e:
            print(f"CSV 파싱 오류: {csv_file}, {e}")
        
        return metrics
    
    def get_aggregated_metrics(self, csv_dir: Path) -> Optional[PerformanceMetrics]:
        """Aggregated 메트릭 추출"""
        stats_file = csv_dir / "stats.csv"
        
        if not stats_file.exists():
            return None
        
        try:
            with open(stats_file, 'r', encoding='utf-8') as f:
                reader = csv.DictReader(f)
                for row in reader:
                    if row.get('Name') == 'Aggregated':
                        return PerformanceMetrics(
                            total_requests=int(row.get('Request Count', 0)),
                            total_failures=int(row.get('Failure Count', 0)),
                            failure_rate=float(row.get('Failure Rate', 0)),
                            avg_response_time=float(row.get('Average Response Time', 0)),
                            median_response_time=float(row.get('Median Response Time', 0)),
                            min_response_time=float(row.get('Min Response Time', 0)),
                            max_response_time=float(row.get('Max Response Time', 0)),
                            p95_response_time=float(row.get('95%', 0)),
                            p99_response_time=float(row.get('99%', 0)),
                            requests_per_second=float(row.get('Requests/s', 0)),
                            total_time=float(row.get('Total Request Time', 0))
                        )
        except Exception as e:
            print(f"Aggregated 메트릭 추출 오류: {e}")
        
        return None
    
    def compare(self) -> Dict:
        """성능 비교 분석"""
        before_metrics = self.get_aggregated_metrics(self.before_csv_dir)
        after_metrics = self.get_aggregated_metrics(self.after_csv_dir)
        
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


def main():
    """메인 함수"""
    if len(sys.argv) < 3:
        print("사용법: python compare_performance.py <before_csv_dir> <after_csv_dir>")
        print("예시: python compare_performance.py results_monolith_vu100 results_msa_vu100")
        sys.exit(1)
    
    before_csv_dir = sys.argv[1]
    after_csv_dir = sys.argv[2]
    
    comparator = PerformanceComparator(before_csv_dir, after_csv_dir)
    comparison = comparator.compare()
    
    if comparison:
        comparator.generate_report(comparison, "performance_comparison.json")
    else:
        print("❌ 성능 비교 실패: 메트릭을 찾을 수 없습니다.")


if __name__ == "__main__":
    main()
