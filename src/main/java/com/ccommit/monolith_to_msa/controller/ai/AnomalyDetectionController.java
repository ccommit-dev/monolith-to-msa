package com.ccommit.monolith_to_msa.controller.ai;

import com.ccommit.monolith_to_msa.domain.baseline.BaselinePattern;
import com.ccommit.monolith_to_msa.domain.metrics.TrafficMetric;
import com.ccommit.monolith_to_msa.repository.baseline.BaselinePatternRepository;
import com.ccommit.monolith_to_msa.repository.metrics.TrafficMetricRepository;
import com.ccommit.monolith_to_msa.service.alert.AlertService;
import com.ccommit.monolith_to_msa.service.baseline.BaselineLearningService;
import com.ccommit.monolith_to_msa.service.connection.ConnectionPoolMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI 이상 탐지 컨트롤러
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AnomalyDetectionController {
    
    private final TrafficMetricRepository metricRepository;
    private final BaselinePatternRepository baselineRepository;
    private final BaselineLearningService baselineLearningService;
    private final ConnectionPoolMonitorService connectionPoolMonitorService;
    private final AlertService alertService;
    
    /**
     * Baseline 패턴 조회
     */
    @GetMapping("/baseline")
    public ResponseEntity<Map<String, Object>> getBaselinePattern(
            @RequestParam(required = false) String endpoint
    ) {
        Map<String, Object> response = new HashMap<>();
        
        if (endpoint != null) {
            Optional<BaselinePattern> baseline = baselineRepository.findFirstByEndpointOrderByLearnedAtDesc(endpoint);
            if (baseline.isPresent()) {
                response.put("baseline", baseline.get());
            } else {
                response.put("message", "Baseline 패턴이 없습니다.");
            }
        } else {
            List<BaselinePattern> baselines = baselineRepository.findAll();
            response.put("baselines", baselines);
        }
        
        return ResponseEntity.ok(response);
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
     * Baseline 패턴 수동 학습
     */
    @PostMapping("/baseline/learn")
    public ResponseEntity<Map<String, String>> learnBaseline(
            @RequestParam(required = false) String endpoint
    ) {
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
        LocalDateTime now = LocalDateTime.now();
        
        if (endpoint != null) {
            baselineLearningService.learnBaselineForEndpoint(endpoint, sevenDaysAgo, now);
            return ResponseEntity.ok(Map.of("message", "Baseline 패턴 학습 완료: " + endpoint));
        } else {
            // 모든 엔드포인트 학습
            baselineLearningService.learnBaselinePatterns();
            return ResponseEntity.ok(Map.of("message", "모든 엔드포인트 Baseline 패턴 학습 완료"));
        }
    }
    
    /**
     * 커넥션 풀 상태 조회
     */
    @GetMapping("/connection-pool/status")
    public ResponseEntity<ConnectionPoolMonitorService.ConnectionPoolStatus> getConnectionPoolStatus() {
        ConnectionPoolMonitorService.ConnectionPoolStatus status = 
                connectionPoolMonitorService.getConnectionPoolStatus();
        return ResponseEntity.ok(status);
    }
    
    /**
     * 알림 통계 조회
     */
    @GetMapping("/alerts/statistics")
    public ResponseEntity<AlertService.AlertStatistics> getAlertStatistics() {
        AlertService.AlertStatistics statistics = alertService.getAlertStatistics();
        return ResponseEntity.ok(statistics);
    }
}
