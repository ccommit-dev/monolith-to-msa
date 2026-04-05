package com.ccommit.monolith_to_msa.controller.ai;

import com.ccommit.monolith_to_msa.domain.baseline.BaselinePattern;
import com.ccommit.monolith_to_msa.domain.metrics.TrafficMetric;
import com.ccommit.monolith_to_msa.repository.baseline.BaselinePatternRepository;
import com.ccommit.monolith_to_msa.repository.metrics.TrafficMetricRepository;
import com.ccommit.monolith_to_msa.service.alert.AlertService;
import com.ccommit.monolith_to_msa.service.baseline.BaselineLearningService;
import com.ccommit.monolith_to_msa.service.connection.ConnectionPoolMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
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
    public ResponseEntity<?> getAnomalies(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestParam(defaultValue = "200") int limit
    ) {
        // 대용량 응답으로 인한 타임아웃을 막기 위해 조회 건수를 제한한다.
        int safeLimit = Math.max(1, Math.min(limit, 1000));

        if (start == null) {
            start = LocalDateTime.now().minusHours(1);
        }
        if (end == null) {
            end = LocalDateTime.now();
        }

        if (start.isAfter(end)) {
            return ResponseEntity.badRequest().build();
        }
        
        try {
            List<TrafficMetric> anomalies = metricRepository
                    .findByIsAnomalyTrueAndTimestampBetweenOrderByTimestampDesc(
                            start,
                            end,
                            PageRequest.of(0, safeLimit)
                    )
                    .getContent();
            return ResponseEntity.ok(anomalies);
        } catch (RuntimeException ex) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "ANOMALY_QUERY_TIMEOUT");
            error.put("message", "이상치 조회가 지연되어 요청을 중단했습니다. limit 값을 줄여 다시 시도하세요.");
            return ResponseEntity.status(503).body(error);
        }
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
