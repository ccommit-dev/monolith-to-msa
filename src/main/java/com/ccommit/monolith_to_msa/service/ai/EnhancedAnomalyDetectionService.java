package com.ccommit.monolith_to_msa.service.ai;

import com.ccommit.monolith_to_msa.domain.baseline.BaselinePattern;
import com.ccommit.monolith_to_msa.domain.metrics.TrafficMetric;
import com.ccommit.monolith_to_msa.repository.baseline.BaselinePatternRepository;
import com.ccommit.monolith_to_msa.repository.metrics.TrafficMetricRepository;
import com.ccommit.monolith_to_msa.service.alert.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 향상된 AI 이상 탐지 서비스
 * - Baseline 기반 이상 탐지
 * - False Positive 최소화
 * - 커넥션 풀 고갈 자동 감지
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnhancedAnomalyDetectionService {
    
    private final TrafficMetricRepository metricRepository;
    private final BaselinePatternRepository baselineRepository;
    private final AlertService alertService;
    
    // False Positive 최소화를 위한 설정
    private static final int MIN_CONSECUTIVE_ANOMALIES = 3; // 연속 이상치 최소 개수
    private static final double CONFIDENCE_THRESHOLD = 0.8; // 신뢰도 임계값
    
    /**
     * 실시간 이상 탐지 (1분마다)
     */
    @Scheduled(fixedRate = 60000) // 1분마다
    @Transactional
    public void detectAnomalies() {
        LocalDateTime oneMinuteAgo = LocalDateTime.now().minusMinutes(1);
        List<TrafficMetric> recentMetrics = metricRepository.findByTimestampAfter(oneMinuteAgo);
        
        for (TrafficMetric metric : recentMetrics) {
            if (metric.getIsAnomaly() != null && metric.getIsAnomaly()) {
                continue; // 이미 이상치로 판단된 경우 스킵
            }
            
            Optional<BaselinePattern> baselineOpt = baselineRepository.findFirstByEndpointOrderByLearnedAtDesc(
                    metric.getEndpoint()
            );
            
            if (baselineOpt.isEmpty()) {
                log.debug("엔드포인트 {}에 대한 Baseline 패턴이 없습니다. 이상 탐지를 건너뜁니다.", metric.getEndpoint());
                continue;
            }
            
            BaselinePattern baseline = baselineOpt.get();
            AnomalyDetectionResult result = detectAnomalyWithBaseline(metric, baseline);
            
            if (result.isAnomaly && result.confidence >= CONFIDENCE_THRESHOLD) {
                // False Positive 최소화: 연속 이상치 확인
                if (isConsecutiveAnomaly(metric.getEndpoint(), metric.getTimestamp())) {
                    metric.setIsAnomaly(true);
                    metric.setAnomalyScore(result.score);
                    metric.setAnomalyReason(result.reason);
                    metricRepository.save(metric);
                    
                    // 알림 발송
                    alertService.sendAnomalyAlert(metric, result);
                    
                    log.warn("이상치 감지 (신뢰도: {:.2f}): endpoint={}, score={}, reason={}",
                            result.confidence, metric.getEndpoint(), result.score, result.reason);
                } else {
                    log.debug("False Positive 가능성: 연속 이상치가 아닙니다. endpoint={}", metric.getEndpoint());
                }
            }
        }
    }
    
    /**
     * Baseline 기반 이상 탐지
     */
    private AnomalyDetectionResult detectAnomalyWithBaseline(TrafficMetric metric, BaselinePattern baseline) {
        double score = 0.0;
        double confidence = 0.0;
        StringBuilder reasons = new StringBuilder();
        
        // 1. TPS 이상치 감지 (가중치: 0.25)
        double tpsZScore = calculateZScore(metric.getTps(), baseline.getBaselineTPS(), baseline.getBaselineTPSStdDev());
        if (Math.abs(tpsZScore) > 2.5) { // 2.5 표준편차 이상
            double tpsScore = Math.min(Math.abs(tpsZScore) / 5.0, 1.0); // 0.0 ~ 1.0 정규화
            score += tpsScore * 0.25;
            confidence += 0.15;
            reasons.append(String.format("TPS 이상 (Z-score: %.2f, 범위: %.2f~%.2f), ", 
                    tpsZScore, baseline.getBaselineTPSMin(), baseline.getBaselineTPSMax()));
        }
        
        // 2. Latency 이상치 감지 (가중치: 0.25)
        double latencyZScore = calculateZScore(metric.getAvgLatency(), baseline.getBaselineLatency(), 
                baseline.getBaselineLatencyStdDev());
        if (Math.abs(latencyZScore) > 2.5) {
            double latencyScore = Math.min(Math.abs(latencyZScore) / 5.0, 1.0);
            score += latencyScore * 0.25;
            confidence += 0.15;
            reasons.append(String.format("Latency 이상 (Z-score: %.2f, 범위: %.2f~%.2f), ", 
                    latencyZScore, baseline.getBaselineLatencyMin(), baseline.getBaselineLatencyMax()));
        }
        
        // 3. Error Rate 이상치 감지 (가중치: 0.2)
        if (metric.getErrorRate() > baseline.getBaselineErrorRate() + 2.5 * baseline.getBaselineErrorRateStdDev()) {
            score += 0.2;
            confidence += 0.1;
            reasons.append(String.format("Error Rate 높음 (%.2f%%, 기준: %.2f%%), ", 
                    metric.getErrorRate(), baseline.getBaselineErrorRate()));
        }
        
        // 4. 커넥션 풀 고갈 감지 (가중치: 0.15) - Part 2/3 병목 자동 감지
        if (metric.getConnectionPoolUsage() > 90.0) {
            score += 0.15;
            confidence += 0.2; // 커넥션 풀 고갈은 높은 신뢰도
            reasons.append(String.format("커넥션 풀 고갈 (%.2f%%, 기준: %.2f%%), ", 
                    metric.getConnectionPoolUsage(), baseline.getBaselineConnectionPoolUsage()));
        } else if (metric.getConnectionPoolUsage() > baseline.getBaselineConnectionPoolUsage() + 
                2.5 * baseline.getBaselineConnectionPoolUsageStdDev()) {
            score += 0.1;
            confidence += 0.1;
            reasons.append(String.format("커넥션 풀 사용률 높음 (%.2f%%), ", metric.getConnectionPoolUsage()));
        }
        
        // 5. Resource 사용률 이상치 감지 (가중치: 0.15)
        if (metric.getCpuUsage() > 85.0 || metric.getCpuUsage() > baseline.getBaselineCpuUsage() + 
                2.5 * baseline.getBaselineCpuUsageStdDev()) {
            score += 0.075;
            confidence += 0.05;
            reasons.append(String.format("CPU 사용률 높음 (%.2f%%), ", metric.getCpuUsage()));
        }
        
        if (metric.getMemoryUsage() > 90.0 || metric.getMemoryUsage() > baseline.getBaselineMemoryUsage() + 
                2.5 * baseline.getBaselineMemoryUsageStdDev()) {
            score += 0.075;
            confidence += 0.05;
            reasons.append(String.format("Memory 사용률 높음 (%.2f%%), ", metric.getMemoryUsage()));
        }
        
        boolean isAnomaly = score >= baseline.getAnomalyThreshold(); // Baseline의 임계값 사용
        
        return AnomalyDetectionResult.builder()
                .isAnomaly(isAnomaly)
                .score(Math.min(score, 1.0))
                .confidence(Math.min(confidence, 1.0))
                .reason(reasons.toString())
                .build();
    }
    
    /**
     * Z-score 계산
     */
    private double calculateZScore(double value, double mean, double std) {
        if (std == 0) {
            return 0.0;
        }
        return (value - mean) / std;
    }
    
    /**
     * 연속 이상치 확인 (False Positive 최소화)
     */
    private boolean isConsecutiveAnomaly(String endpoint, LocalDateTime timestamp) {
        LocalDateTime fiveMinutesAgo = timestamp.minusMinutes(5);
        List<TrafficMetric> recentMetrics = metricRepository.findByEndpointAndTimestampBetween(
                endpoint, 
                fiveMinutesAgo, 
                timestamp
        );
        
        // 최근 5분간의 메트릭 중 이상치 개수 확인
        long anomalyCount = recentMetrics.stream()
                .filter(m -> m.getIsAnomaly() != null && m.getIsAnomaly())
                .count();
        
        return anomalyCount >= MIN_CONSECUTIVE_ANOMALIES;
    }
    
    /**
     * 이상 탐지 결과 데이터 클래스
     */
    @lombok.Data
    @lombok.Builder
    public static class AnomalyDetectionResult {
        public boolean isAnomaly;
        public double score;
        public double confidence; // 신뢰도 (0.0 ~ 1.0)
        public String reason;
    }
}
