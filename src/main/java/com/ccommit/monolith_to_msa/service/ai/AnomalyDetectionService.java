package com.ccommit.monolith_to_msa.service.ai;

import com.ccommit.monolith_to_msa.domain.metrics.TrafficMetric;
import com.ccommit.monolith_to_msa.repository.metrics.TrafficMetricRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI 기반 이상 탐지 서비스
 * - 정상 패턴 학습
 * - 이상치 감지
 * - 이상치 점수 계산
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyDetectionService {
    
    private final TrafficMetricRepository metricRepository;
    
    // 정상 패턴 통계 (학습 데이터 기반)
    private PatternStatistics normalPattern;
    
    /**
     * 정상 패턴 학습 (최근 1시간 데이터 기반)
     */
    /** 첫 실행은 앱 기동 직후가 아니라 initialDelay 이후(실습 시 곧바로 학습 가능하도록). */
    @Scheduled(fixedRate = 3_600_000L, initialDelay = 90_000L)
    public void learnNormalPattern() {
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        List<TrafficMetric> recentMetrics = metricRepository.findByTimestampBetween(
                oneHourAgo, 
                LocalDateTime.now()
        );
        
        if (recentMetrics.isEmpty()) {
            log.warn("학습 데이터가 없습니다. 이상 탐지를 수행할 수 없습니다.");
            return;
        }
        
        // 통계 계산
        normalPattern = calculatePatternStatistics(recentMetrics);
        
        log.info("정상 패턴 학습 완료: 샘플 수={}, 평균 TPS={}, 평균 Latency={}ms",
                recentMetrics.size(), normalPattern.avgTPS, normalPattern.avgLatency);
    }
    
    /**
     * 이상 탐지 수행 (1분마다)
     */
    @Scheduled(fixedRate = 60000) // 1분마다
    @Transactional
    public void detectAnomalies() {
        if (normalPattern == null) {
            log.debug("정상 패턴이 학습되지 않았습니다. 이상 탐지를 건너뜁니다.");
            return;
        }
        
        LocalDateTime oneMinuteAgo = LocalDateTime.now().minusMinutes(1);
        List<TrafficMetric> recentMetrics = metricRepository.findByTimestampAfter(oneMinuteAgo);
        
        for (TrafficMetric metric : recentMetrics) {
            if (metric.getIsAnomaly() != null && metric.getIsAnomaly()) {
                continue; // 이미 이상치로 판단된 경우 스킵
            }
            
            AnomalyResult result = detectAnomaly(metric);
            
            if (result.isAnomaly) {
                metric.setIsAnomaly(true);
                metric.setAnomalyScore(result.score);
                metric.setAnomalyReason(result.reason);
                metricRepository.save(metric);
                
                log.warn("이상치 감지: endpoint={}, score={}, reason={}",
                        metric.getEndpoint(), result.score, result.reason);
            }
        }
    }
    
    /**
     * 이상치 감지 (Z-score 기반)
     */
    private AnomalyResult detectAnomaly(TrafficMetric metric) {
        double score = 0.0;
        StringBuilder reasons = new StringBuilder();
        
        // TPS 이상치 감지
        double tpsZScore = calculateZScore(metric.getTps(), normalPattern.avgTPS, normalPattern.stdTPS);
        if (Math.abs(tpsZScore) > 2.0) { // 2 표준편차 이상
            score += 0.3;
            reasons.append(String.format("TPS 이상 (Z-score: %.2f), ", tpsZScore));
        }
        
        // Latency 이상치 감지
        double latencyZScore = calculateZScore(metric.getAvgLatency(), normalPattern.avgLatency, normalPattern.stdLatency);
        if (Math.abs(latencyZScore) > 2.0) {
            score += 0.3;
            reasons.append(String.format("Latency 이상 (Z-score: %.2f), ", latencyZScore));
        }
        
        // Error Rate 이상치 감지
        if (metric.getErrorRate() > normalPattern.avgErrorRate + 2 * normalPattern.stdErrorRate) {
            score += 0.2;
            reasons.append(String.format("Error Rate 높음 (%.2f%%), ", metric.getErrorRate()));
        }
        
        // Resource 사용률 이상치 감지
        if (metric.getCpuUsage() > 80.0) {
            score += 0.1;
            reasons.append(String.format("CPU 사용률 높음 (%.2f%%), ", metric.getCpuUsage()));
        }
        
        if (metric.getMemoryUsage() > 85.0) {
            score += 0.1;
            reasons.append(String.format("Memory 사용률 높음 (%.2f%%), ", metric.getMemoryUsage()));
        }
        
        boolean isAnomaly = score > 0.5; // 임계값 0.5
        
        return AnomalyResult.builder()
                .isAnomaly(isAnomaly)
                .score(Math.min(score, 1.0))
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
     * 패턴 통계 계산
     */
    private PatternStatistics calculatePatternStatistics(List<TrafficMetric> metrics) {
        List<Double> tpsList = metrics.stream()
                .map(TrafficMetric::getTps)
                .collect(Collectors.toList());
        
        List<Double> latencyList = metrics.stream()
                .map(TrafficMetric::getAvgLatency)
                .collect(Collectors.toList());
        
        List<Double> errorRateList = metrics.stream()
                .map(TrafficMetric::getErrorRate)
                .collect(Collectors.toList());
        
        return PatternStatistics.builder()
                .avgTPS(calculateMean(tpsList))
                .stdTPS(calculateStdDev(tpsList))
                .avgLatency(calculateMean(latencyList))
                .stdLatency(calculateStdDev(latencyList))
                .avgErrorRate(calculateMean(errorRateList))
                .stdErrorRate(calculateStdDev(errorRateList))
                .build();
    }
    
    /**
     * 평균 계산
     */
    private double calculateMean(List<Double> values) {
        return values.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
    }
    
    /**
     * 표준편차 계산
     */
    private double calculateStdDev(List<Double> values) {
        double mean = calculateMean(values);
        double variance = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0.0);
        return Math.sqrt(variance);
    }
    
    /**
     * 패턴 통계 데이터 클래스
     */
    @lombok.Data
    @lombok.Builder
    private static class PatternStatistics {
        private double avgTPS;
        private double stdTPS;
        private double avgLatency;
        private double stdLatency;
        private double avgErrorRate;
        private double stdErrorRate;
    }
    
    /**
     * 이상치 결과 데이터 클래스
     */
    @lombok.Data
    @lombok.Builder
    private static class AnomalyResult {
        private boolean isAnomaly;
        private double score;
        private String reason;
    }
}
