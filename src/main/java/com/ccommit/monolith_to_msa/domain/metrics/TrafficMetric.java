package com.ccommit.monolith_to_msa.domain.metrics;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 트래픽 메트릭 엔티티
 * - TPS, Latency, Error Rate, Resource 사용률 수집
 */
@Entity
@Table(name = "traffic_metrics", indexes = {
    @Index(name = "idx_timestamp", columnList = "timestamp"),
    @Index(name = "idx_endpoint", columnList = "endpoint"),
    @Index(name = "idx_timestamp_endpoint", columnList = "timestamp, endpoint")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrafficMetric {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String endpoint;
    
    @Column(nullable = false)
    private LocalDateTime timestamp;
    
    // TPS (Transactions Per Second)
    @Column(nullable = false)
    private Double tps;
    
    // Latency (응답 시간)
    @Column(nullable = false)
    private Double avgLatency;  // 평균 응답 시간 (ms)
    @Column(nullable = false)
    private Double p95Latency;  // 95% 응답 시간 (ms)
    @Column(nullable = false)
    private Double p99Latency;  // 99% 응답 시간 (ms)
    
    // Error Rate
    @Column(nullable = false)
    private Double errorRate;  // 에러율 (%)
    @Column(nullable = false)
    private Long requestCount;
    @Column(nullable = false)
    private Long errorCount;
    
    // Resource 사용률
    @Column(nullable = false)
    private Double cpuUsage;  // CPU 사용률 (%)
    @Column(nullable = false)
    private Double memoryUsage;  // 메모리 사용률 (%)
    @Column(nullable = false)
    private Double connectionPoolUsage;  // 커넥션 풀 사용률 (%)
    
    // AI 이상 탐지 결과
    @Column
    private Boolean isAnomaly;  // 이상치 여부
    @Column
    private Double anomalyScore;  // 이상치 점수 (0.0 ~ 1.0)
    @Column(length = 500)
    private String anomalyReason;  // 이상치 원인
    
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
