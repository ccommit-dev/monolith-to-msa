package com.ccommit.monolith_to_msa.controller.metrics;

import com.ccommit.monolith_to_msa.domain.metrics.TrafficMetric;
import com.ccommit.monolith_to_msa.repository.metrics.TrafficMetricRepository;
import com.ccommit.monolith_to_msa.service.pipeline.DataPipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 트래픽 메트릭 조회 컨트롤러
 * - Grafana에서 조회할 수 있는 API 제공
 */
@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
public class TrafficMetricsController {
    
    private final TrafficMetricRepository metricRepository;
    private final DataPipelineService pipelineService;
    
    /**
     * 메트릭 조회 (시간 범위)
     */
    @GetMapping("/traffic")
    public ResponseEntity<List<TrafficMetric>> getTrafficMetrics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestParam(required = false) String endpoint
    ) {
        if (start == null) {
            start = LocalDateTime.now().minusHours(1);
        }
        if (end == null) {
            end = LocalDateTime.now();
        }
        
        List<TrafficMetric> metrics;
        if (endpoint != null) {
            metrics = metricRepository.findByEndpointAndTimestampBetween(endpoint, start, end);
        } else {
            metrics = metricRepository.findByTimestampBetween(start, end);
        }
        
        return ResponseEntity.ok(metrics);
    }
    
    /**
     * 이상치 메트릭 조회
     */
    @GetMapping("/anomalies")
    public ResponseEntity<List<TrafficMetric>> getAnomalies(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end
    ) {
        if (start == null) {
            start = LocalDateTime.now().minusHours(24);
        }
        if (end == null) {
            end = LocalDateTime.now();
        }
        
        List<TrafficMetric> anomalies = metricRepository.findByIsAnomalyTrueAndTimestampBetween(start, end);
        return ResponseEntity.ok(anomalies);
    }
    
    /**
     * 파이프라인 상태 조회
     */
    @GetMapping("/pipeline/status")
    public ResponseEntity<Map<String, Object>> getPipelineStatus() {
        DataPipelineService.PipelineStatus status = pipelineService.getPipelineStatus();
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", status.getStatus());
        response.put("totalMetrics", status.getTotalMetrics());
        response.put("anomalyCount", status.getAnomalyCount());
        response.put("lastUpdateTime", status.getLastUpdateTime());
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * 파이프라인 통계 조회
     */
    @GetMapping("/pipeline/statistics")
    public ResponseEntity<Map<String, Object>> getPipelineStatistics() {
        DataPipelineService.PipelineStatistics statistics = pipelineService.getStatistics();
        
        Map<String, Object> response = new HashMap<>();
        response.put("totalMetrics", statistics.getTotalMetrics());
        response.put("avgTPS", statistics.getAvgTPS());
        response.put("avgLatency", statistics.getAvgLatency());
        response.put("avgErrorRate", statistics.getAvgErrorRate());
        response.put("anomalyCount", statistics.getAnomalyCount());
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Prometheus 형식 메트릭 조회 (간단한 예시)
     */
    @GetMapping("/prometheus")
    public ResponseEntity<String> getPrometheusMetrics() {
        LocalDateTime oneMinuteAgo = LocalDateTime.now().minusMinutes(1);
        List<TrafficMetric> recentMetrics = metricRepository.findRecentMetrics(oneMinuteAgo);
        
        StringBuilder prometheus = new StringBuilder();
        for (TrafficMetric metric : recentMetrics) {
            String endpoint = metric.getEndpoint().replace("/", "_");
            prometheus.append(String.format("traffic_tps{endpoint=\"%s\"} %.2f\n", endpoint, metric.getTps()));
            prometheus.append(String.format("traffic_latency_avg{endpoint=\"%s\"} %.2f\n", endpoint, metric.getAvgLatency()));
            prometheus.append(String.format("traffic_error_rate{endpoint=\"%s\"} %.2f\n", endpoint, metric.getErrorRate()));
        }
        
        return ResponseEntity.ok(prometheus.toString());
    }
}
