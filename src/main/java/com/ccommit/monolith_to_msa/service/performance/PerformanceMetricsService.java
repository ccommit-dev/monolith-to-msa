package com.ccommit.monolith_to_msa.service.performance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 성능 지표 수집 서비스
 * - TPS (Transactions Per Second) 측정
 * - 응답 시간 통계
 * - 에러율 계산
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerformanceMetricsService {
    
    private final Map<String, AtomicLong> requestCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> errorCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> totalResponseTime = new ConcurrentHashMap<>();
    private final Map<String, Long> startTimes = new ConcurrentHashMap<>();
    
    /**
     * 요청 시작 시간 기록
     */
    public void recordRequestStart(String endpoint) {
        startTimes.put(Thread.currentThread().getId() + ":" + endpoint, System.currentTimeMillis());
    }
    
    /**
     * 요청 완료 기록
     */
    public void recordRequestComplete(String endpoint, boolean success) {
        String key = Thread.currentThread().getId() + ":" + endpoint;
        Long startTime = startTimes.remove(key);
        
        if (startTime != null) {
            long responseTime = System.currentTimeMillis() - startTime;
            
            requestCounts.computeIfAbsent(endpoint, k -> new AtomicLong(0)).incrementAndGet();
            totalResponseTime.computeIfAbsent(endpoint, k -> new AtomicLong(0)).addAndGet(responseTime);
            
            if (!success) {
                errorCounts.computeIfAbsent(endpoint, k -> new AtomicLong(0)).incrementAndGet();
            }
        }
    }
    
    /**
     * TPS 계산
     */
    public double calculateTPS(String endpoint, long durationSeconds) {
        AtomicLong count = requestCounts.get(endpoint);
        if (count == null || durationSeconds == 0) {
            return 0.0;
        }
        return count.get() / (double) durationSeconds;
    }
    
    /**
     * 평균 응답 시간 계산
     */
    public double calculateAvgResponseTime(String endpoint) {
        AtomicLong count = requestCounts.get(endpoint);
        AtomicLong totalTime = totalResponseTime.get(endpoint);
        
        if (count == null || totalTime == null || count.get() == 0) {
            return 0.0;
        }
        
        return totalTime.get() / (double) count.get();
    }
    
    /**
     * 에러율 계산
     */
    public double calculateErrorRate(String endpoint) {
        AtomicLong requestCount = requestCounts.get(endpoint);
        AtomicLong errorCount = errorCounts.get(endpoint);
        
        if (requestCount == null || requestCount.get() == 0) {
            return 0.0;
        }
        
        if (errorCount == null) {
            return 0.0;
        }
        
        return (errorCount.get() / (double) requestCount.get()) * 100.0;
    }
    
    /**
     * 성능 지표 조회
     */
    public PerformanceMetrics getMetrics(String endpoint, long durationSeconds) {
        return PerformanceMetrics.builder()
                .endpoint(endpoint)
                .requestCount(requestCounts.getOrDefault(endpoint, new AtomicLong(0)).get())
                .errorCount(errorCounts.getOrDefault(endpoint, new AtomicLong(0)).get())
                .tps(calculateTPS(endpoint, durationSeconds))
                .avgResponseTime(calculateAvgResponseTime(endpoint))
                .errorRate(calculateErrorRate(endpoint))
                .timestamp(LocalDateTime.now())
                .build();
    }
    
    /**
     * 모든 지표 초기화
     */
    public void reset() {
        requestCounts.clear();
        errorCounts.clear();
        totalResponseTime.clear();
        startTimes.clear();
        log.info("성능 지표 초기화 완료");
    }
}
