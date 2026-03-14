package com.ccommit.monolith_to_msa.service.performance;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 성능 지표 데이터 클래스
 */
@Data
@Builder
public class PerformanceMetrics {
    private String endpoint;
    private long requestCount;
    private long errorCount;
    private double tps;
    private double avgResponseTime;
    private double errorRate;
    private LocalDateTime timestamp;
}
