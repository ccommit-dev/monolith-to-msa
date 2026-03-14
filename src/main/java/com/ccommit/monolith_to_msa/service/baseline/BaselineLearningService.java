package com.ccommit.monolith_to_msa.service.baseline;

import com.ccommit.monolith_to_msa.domain.baseline.BaselinePattern;
import com.ccommit.monolith_to_msa.domain.metrics.TrafficMetric;
import com.ccommit.monolith_to_msa.repository.baseline.BaselinePatternRepository;
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
 * Baseline Learning 서비스
 * - 7일간의 정상 패턴 학습
 * - 기준선 설정
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BaselineLearningService {
    
    private final TrafficMetricRepository metricRepository;
    private final BaselinePatternRepository baselineRepository;
    
    private static final int LEARNING_DAYS = 7;
    private static final double DEFAULT_ANOMALY_THRESHOLD = 0.7; // False Positive 최소화를 위해 0.5 → 0.7로 상향
    private static final double DEFAULT_CONFIDENCE_LEVEL = 0.95; // 95% 신뢰도
    
    /**
     * Baseline 패턴 학습 (매일 자정에 실행)
     */
    @Scheduled(cron = "0 0 0 * * *") // 매일 자정
    @Transactional
    public void learnBaselinePatterns() {
        log.info("Baseline 패턴 학습 시작 (최근 {}일 데이터)", LEARNING_DAYS);
        
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(LEARNING_DAYS);
        LocalDateTime now = LocalDateTime.now();
        
        // 모든 엔드포인트에 대해 Baseline 학습
        List<String> endpoints = metricRepository.findDistinctEndpoints();
        
        if (endpoints.isEmpty()) {
            log.warn("학습할 엔드포인트가 없습니다.");
            return;
        }
        
        for (String endpoint : endpoints) {
            learnBaselineForEndpoint(endpoint, sevenDaysAgo, now);
        }
        
        log.info("Baseline 패턴 학습 완료: {}개 엔드포인트", endpoints.size());
    }
    
    /**
     * 특정 엔드포인트의 Baseline 패턴 학습
     */
    @Transactional
    public void learnBaselineForEndpoint(String endpoint, LocalDateTime startTime, LocalDateTime endTime) {
        List<TrafficMetric> metrics = metricRepository.findByEndpointAndTimestampBetween(
                endpoint, 
                startTime, 
                endTime
        );
        
        if (metrics.isEmpty()) {
            log.warn("엔드포인트 {}에 대한 학습 데이터가 없습니다.", endpoint);
            return;
        }
        
        // 이상치 제외 (이미 이상치로 판단된 데이터는 제외)
        List<TrafficMetric> normalMetrics = metrics.stream()
                .filter(m -> m.getIsAnomaly() == null || !m.getIsAnomaly())
                .collect(Collectors.toList());
        
        if (normalMetrics.size() < 100) {
            log.warn("엔드포인트 {}에 대한 정상 데이터가 부족합니다. (최소 100개 필요, 현재: {})", 
                    endpoint, normalMetrics.size());
            return;
        }
        
        // 통계 계산
        BaselineStatistics stats = calculateBaselineStatistics(normalMetrics);
        
        // Baseline 패턴 저장
        BaselinePattern baseline = BaselinePattern.builder()
                .endpoint(endpoint)
                .learnedAt(LocalDateTime.now())
                .baselineTPS(stats.avgTPS)
                .baselineTPSStdDev(stats.stdTPS)
                .baselineTPSMin(stats.minTPS)
                .baselineTPSMax(stats.maxTPS)
                .baselineLatency(stats.avgLatency)
                .baselineLatencyStdDev(stats.stdLatency)
                .baselineLatencyMin(stats.minLatency)
                .baselineLatencyMax(stats.maxLatency)
                .baselineErrorRate(stats.avgErrorRate)
                .baselineErrorRateStdDev(stats.stdErrorRate)
                .baselineCpuUsage(stats.avgCpuUsage)
                .baselineCpuUsageStdDev(stats.stdCpuUsage)
                .baselineMemoryUsage(stats.avgMemoryUsage)
                .baselineMemoryUsageStdDev(stats.stdMemoryUsage)
                .baselineConnectionPoolUsage(stats.avgConnectionPoolUsage)
                .baselineConnectionPoolUsageStdDev(stats.stdConnectionPoolUsage)
                .sampleCount((long) normalMetrics.size())
                .learningStartTime(startTime)
                .learningEndTime(endTime)
                .anomalyThreshold(DEFAULT_ANOMALY_THRESHOLD)
                .confidenceLevel(DEFAULT_CONFIDENCE_LEVEL)
                .build();
        
        // 기존 Baseline 패턴이 있으면 업데이트, 없으면 생성
        baselineRepository.findByEndpoint(endpoint)
                .ifPresentOrElse(
                        existing -> {
                            baseline.setId(existing.getId());
                            baselineRepository.save(baseline);
                            log.info("Baseline 패턴 업데이트: endpoint={}, samples={}", endpoint, normalMetrics.size());
                        },
                        () -> {
                            baselineRepository.save(baseline);
                            log.info("Baseline 패턴 생성: endpoint={}, samples={}", endpoint, normalMetrics.size());
                        }
                );
    }
    
    /**
     * Baseline 통계 계산
     */
    private BaselineStatistics calculateBaselineStatistics(List<TrafficMetric> metrics) {
        List<Double> tpsList = metrics.stream().map(TrafficMetric::getTps).collect(Collectors.toList());
        List<Double> latencyList = metrics.stream().map(TrafficMetric::getAvgLatency).collect(Collectors.toList());
        List<Double> errorRateList = metrics.stream().map(TrafficMetric::getErrorRate).collect(Collectors.toList());
        List<Double> cpuUsageList = metrics.stream().map(TrafficMetric::getCpuUsage).collect(Collectors.toList());
        List<Double> memoryUsageList = metrics.stream().map(TrafficMetric::getMemoryUsage).collect(Collectors.toList());
        List<Double> connectionPoolUsageList = metrics.stream()
                .map(TrafficMetric::getConnectionPoolUsage)
                .collect(Collectors.toList());
        
        return BaselineStatistics.builder()
                .avgTPS(calculateMean(tpsList))
                .stdTPS(calculateStdDev(tpsList))
                .minTPS(tpsList.stream().mapToDouble(Double::doubleValue).min().orElse(0.0))
                .maxTPS(tpsList.stream().mapToDouble(Double::doubleValue).max().orElse(0.0))
                .avgLatency(calculateMean(latencyList))
                .stdLatency(calculateStdDev(latencyList))
                .minLatency(latencyList.stream().mapToDouble(Double::doubleValue).min().orElse(0.0))
                .maxLatency(latencyList.stream().mapToDouble(Double::doubleValue).max().orElse(0.0))
                .avgErrorRate(calculateMean(errorRateList))
                .stdErrorRate(calculateStdDev(errorRateList))
                .avgCpuUsage(calculateMean(cpuUsageList))
                .stdCpuUsage(calculateStdDev(cpuUsageList))
                .avgMemoryUsage(calculateMean(memoryUsageList))
                .stdMemoryUsage(calculateStdDev(memoryUsageList))
                .avgConnectionPoolUsage(calculateMean(connectionPoolUsageList))
                .stdConnectionPoolUsage(calculateStdDev(connectionPoolUsageList))
                .build();
    }
    
    /**
     * 평균 계산
     */
    private double calculateMean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
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
     * Baseline 통계 데이터 클래스
     */
    @lombok.Data
    @lombok.Builder
    private static class BaselineStatistics {
        private double avgTPS;
        private double stdTPS;
        private double minTPS;
        private double maxTPS;
        private double avgLatency;
        private double stdLatency;
        private double minLatency;
        private double maxLatency;
        private double avgErrorRate;
        private double stdErrorRate;
        private double avgCpuUsage;
        private double stdCpuUsage;
        private double avgMemoryUsage;
        private double stdMemoryUsage;
        private double avgConnectionPoolUsage;
        private double stdConnectionPoolUsage;
    }
}
