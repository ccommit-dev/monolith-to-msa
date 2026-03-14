package com.ccommit.monolith_to_msa.service.pipeline;

import com.ccommit.monolith_to_msa.domain.metrics.TrafficMetric;
import com.ccommit.monolith_to_msa.repository.metrics.TrafficMetricRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 데이터 파이프라인 서비스
 * - 수집 → 저장 → 처리 → 시각화 파이프라인 관리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataPipelineService {
    
    private final TrafficMetricRepository metricRepository;
    
    /**
     * 데이터 파이프라인 실행
     * 1. 수집: MetricCollectorService에서 수집된 데이터
     * 2. 저장: TrafficMetricRepository에 저장
     * 3. 처리: AnomalyDetectionService에서 이상 탐지
     * 4. 시각화: Grafana에서 조회
     */
    public PipelineStatus getPipelineStatus() {
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        List<TrafficMetric> recentMetrics = metricRepository.findByTimestampAfter(oneHourAgo);
        
        long totalMetrics = recentMetrics.size();
        long anomalyCount = recentMetrics.stream()
                .filter(m -> m.getIsAnomaly() != null && m.getIsAnomaly())
                .count();
        
        return PipelineStatus.builder()
                .status("RUNNING")
                .totalMetrics(totalMetrics)
                .anomalyCount(anomalyCount)
                .lastUpdateTime(LocalDateTime.now())
                .build();
    }
    
    /**
     * 파이프라인 통계 조회
     */
    public PipelineStatistics getStatistics() {
        LocalDateTime oneDayAgo = LocalDateTime.now().minusDays(1);
        List<TrafficMetric> metrics = metricRepository.findByTimestampAfter(oneDayAgo);
        
        if (metrics.isEmpty()) {
            return PipelineStatistics.builder()
                    .totalMetrics(0L)
                    .avgTPS(0.0)
                    .avgLatency(0.0)
                    .avgErrorRate(0.0)
                    .anomalyCount(0L)
                    .build();
        }
        
        double avgTPS = metrics.stream()
                .mapToDouble(TrafficMetric::getTps)
                .average()
                .orElse(0.0);
        
        double avgLatency = metrics.stream()
                .mapToDouble(TrafficMetric::getAvgLatency)
                .average()
                .orElse(0.0);
        
        double avgErrorRate = metrics.stream()
                .mapToDouble(TrafficMetric::getErrorRate)
                .average()
                .orElse(0.0);
        
        long anomalyCount = metrics.stream()
                .filter(m -> m.getIsAnomaly() != null && m.getIsAnomaly())
                .count();
        
        return PipelineStatistics.builder()
                .totalMetrics((long) metrics.size())
                .avgTPS(avgTPS)
                .avgLatency(avgLatency)
                .avgErrorRate(avgErrorRate)
                .anomalyCount(anomalyCount)
                .build();
    }
    
    /**
     * 파이프라인 상태 데이터 클래스
     */
    @lombok.Data
    @lombok.Builder
    public static class PipelineStatus {
        private String status;
        private long totalMetrics;
        private long anomalyCount;
        private LocalDateTime lastUpdateTime;
    }
    
    /**
     * 파이프라인 통계 데이터 클래스
     */
    @lombok.Data
    @lombok.Builder
    public static class PipelineStatistics {
        private long totalMetrics;
        private double avgTPS;
        private double avgLatency;
        private double avgErrorRate;
        private long anomalyCount;
    }
}
