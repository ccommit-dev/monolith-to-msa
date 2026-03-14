package com.ccommit.monolith_to_msa.domain.baseline;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Baseline 패턴 엔티티
 * - 7일간의 정상 패턴 학습 결과 저장
 * - 기준선 설정 및 이상 탐지에 사용
 */
@Entity
@Table(name = "baseline_patterns")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BaselinePattern {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String endpoint;
    
    @Column(nullable = false)
    private LocalDateTime learnedAt;
    
    // TPS 기준선
    @Column(nullable = false)
    private Double baselineTPS;
    @Column(nullable = false)
    private Double baselineTPSStdDev;
    @Column(nullable = false)
    private Double baselineTPSMin;
    @Column(nullable = false)
    private Double baselineTPSMax;
    
    // Latency 기준선
    @Column(nullable = false)
    private Double baselineLatency;
    @Column(nullable = false)
    private Double baselineLatencyStdDev;
    @Column(nullable = false)
    private Double baselineLatencyMin;
    @Column(nullable = false)
    private Double baselineLatencyMax;
    
    // Error Rate 기준선
    @Column(nullable = false)
    private Double baselineErrorRate;
    @Column(nullable = false)
    private Double baselineErrorRateStdDev;
    
    // Resource 기준선
    @Column(nullable = false)
    private Double baselineCpuUsage;
    @Column(nullable = false)
    private Double baselineCpuUsageStdDev;
    @Column(nullable = false)
    private Double baselineMemoryUsage;
    @Column(nullable = false)
    private Double baselineMemoryUsageStdDev;
    @Column(nullable = false)
    private Double baselineConnectionPoolUsage;
    @Column(nullable = false)
    private Double baselineConnectionPoolUsageStdDev;
    
    // 학습 데이터 통계
    @Column(nullable = false)
    private Long sampleCount;  // 학습에 사용된 샘플 수
    @Column(nullable = false)
    private LocalDateTime learningStartTime;
    @Column(nullable = false)
    private LocalDateTime learningEndTime;
    
    // False Positive 최소화를 위한 임계값
    @Column(nullable = false)
    private Double anomalyThreshold;  // 이상치 판단 임계값 (기본 0.5)
    @Column(nullable = false)
    private Double confidenceLevel;  // 신뢰도 (0.0 ~ 1.0)
    
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
