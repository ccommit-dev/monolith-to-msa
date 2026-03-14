package com.ccommit.monolith_to_msa.controller.performance;

import com.ccommit.monolith_to_msa.service.performance.PerformanceMetrics;
import com.ccommit.monolith_to_msa.service.performance.PerformanceMetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 성능 지표 조회 컨트롤러
 */
@RestController
@RequestMapping("/api/performance")
@RequiredArgsConstructor
public class PerformanceMetricsController {
    
    private final PerformanceMetricsService metricsService;
    
    /**
     * 성능 지표 조회
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics(
            @RequestParam(required = false) String endpoint,
            @RequestParam(defaultValue = "60") long durationSeconds
    ) {
        Map<String, Object> response = new HashMap<>();
        
        if (endpoint != null) {
            PerformanceMetrics metrics = metricsService.getMetrics(endpoint, durationSeconds);
            response.put("metrics", metrics);
        } else {
            // 모든 엔드포인트 지표 조회 (예시)
            response.put("message", "엔드포인트를 지정해주세요");
        }
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * 성능 지표 초기화
     */
    @GetMapping("/reset")
    public ResponseEntity<Map<String, String>> resetMetrics() {
        metricsService.reset();
        return ResponseEntity.ok(Map.of("message", "성능 지표가 초기화되었습니다"));
    }
}
