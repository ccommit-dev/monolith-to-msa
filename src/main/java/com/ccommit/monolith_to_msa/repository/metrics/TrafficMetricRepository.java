package com.ccommit.monolith_to_msa.repository.metrics;

import com.ccommit.monolith_to_msa.domain.metrics.TrafficMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 트래픽 메트릭 Repository
 */
@Repository
public interface TrafficMetricRepository extends JpaRepository<TrafficMetric, Long> {
    
    /**
     * 특정 시간 범위의 메트릭 조회
     */
    List<TrafficMetric> findByTimestampBetween(LocalDateTime start, LocalDateTime end);
    
    /**
     * 특정 엔드포인트의 메트릭 조회
     */
    List<TrafficMetric> findByEndpointAndTimestampBetween(
            String endpoint, 
            LocalDateTime start, 
            LocalDateTime end
    );
    
    /**
     * 이상치 메트릭 조회
     */
    List<TrafficMetric> findByIsAnomalyTrueAndTimestampBetween(
            LocalDateTime start, 
            LocalDateTime end
    );
    
    /**
     * 최근 N분간의 메트릭 조회
     */
    @Query("SELECT m FROM TrafficMetric m WHERE m.timestamp >= :since ORDER BY m.timestamp DESC")
    List<TrafficMetric> findRecentMetrics(@Param("since") LocalDateTime since);
    
    /**
     * 특정 엔드포인트의 평균 TPS 계산
     */
    @Query("SELECT AVG(m.tps) FROM TrafficMetric m WHERE m.endpoint = :endpoint AND m.timestamp >= :since")
    Double calculateAvgTPS(@Param("endpoint") String endpoint, @Param("since") LocalDateTime since);
    
    /**
     * 특정 엔드포인트의 평균 Latency 계산
     */
    @Query("SELECT AVG(m.avgLatency) FROM TrafficMetric m WHERE m.endpoint = :endpoint AND m.timestamp >= :since")
    Double calculateAvgLatency(@Param("endpoint") String endpoint, @Param("since") LocalDateTime since);
    
    /**
     * 특정 시간 이후의 메트릭 조회
     */
    List<TrafficMetric> findByTimestampAfter(LocalDateTime timestamp);
    
    /**
     * 고유한 엔드포인트 목록 조회
     */
    @Query("SELECT DISTINCT m.endpoint FROM TrafficMetric m")
    List<String> findDistinctEndpoints();
}
