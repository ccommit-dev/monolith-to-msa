package com.ccommit.monolith_to_msa.service.metrics;

import com.ccommit.monolith_to_msa.domain.metrics.TrafficMetric;
import com.ccommit.monolith_to_msa.repository.metrics.TrafficMetricRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 메트릭 수집 서비스
 * - TPS, Latency, Error Rate, Resource 사용률 수집
 * - 주기적으로 메트릭을 수집하여 DB에 저장
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricCollectorService {
    
    private final TrafficMetricRepository metricRepository;
    
    // 실시간 메트릭 수집용 (메모리)
    private final Map<String, MetricWindow> metricWindows = new ConcurrentHashMap<>();
    
    /**
     * 요청 메트릭 기록
     */
    public void recordRequest(String endpoint, long responseTime, boolean success) {
        MetricWindow window = metricWindows.computeIfAbsent(
                endpoint, 
                k -> new MetricWindow()
        );
        
        window.recordRequest(responseTime, success);
    }
    
    /**
     * 주기적으로 메트릭 수집 및 저장 (1분마다)
     */
    @Scheduled(fixedRate = 60000) // 1분마다
    @Transactional
    public void collectAndSaveMetrics() {
        LocalDateTime now = LocalDateTime.now();
        
        for (Map.Entry<String, MetricWindow> entry : metricWindows.entrySet()) {
            String endpoint = entry.getKey();
            MetricWindow window = entry.getValue();
            
            if (window.getRequestCount() == 0) {
                continue; // 요청이 없으면 스킵
            }
            
            // 메트릭 계산
            TrafficMetric metric = TrafficMetric.builder()
                    .endpoint(endpoint)
                    .timestamp(now)
                    .tps(window.calculateTPS())
                    .avgLatency(window.calculateAvgLatency())
                    .p95Latency(window.calculateP95Latency())
                    .p99Latency(window.calculateP99Latency())
                    .errorRate(window.calculateErrorRate())
                    .requestCount(window.getRequestCount())
                    .errorCount(window.getErrorCount())
                    .cpuUsage(getCpuUsage())
                    .memoryUsage(getMemoryUsage())
                    .connectionPoolUsage(getConnectionPoolUsage())
                    .isAnomaly(false) // AI 이상 탐지는 별도 서비스에서 처리
                    .build();
            
            // DB 저장
            metricRepository.save(metric);
            
            // 윈도우 초기화
            window.reset();
            
            log.debug("메트릭 수집 완료: endpoint={}, tps={}, latency={}ms", 
                    endpoint, metric.getTps(), metric.getAvgLatency());
        }
    }
    
    /**
     * CPU 사용률 계산 (간단한 추정)
     */
    private Double getCpuUsage() {
        // 실제 CPU 사용률은 더 정교한 방법 필요 (예: OSHI 라이브러리)
        // 여기서는 간단히 추정
        return Math.random() * 50 + 20; // 20~70% 범위 (예시)
    }
    
    /**
     * 메모리 사용률 계산
     */
    private Double getMemoryUsage() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        long used = memoryBean.getHeapMemoryUsage().getUsed();
        long max = memoryBean.getHeapMemoryUsage().getMax();
        
        if (max > 0) {
            return (used / (double) max) * 100.0;
        }
        return 0.0;
    }
    
    /**
     * 커넥션 풀 사용률 계산 (간단한 추정)
     */
    private Double getConnectionPoolUsage() {
        // 실제 커넥션 풀 사용률은 HikariCP에서 가져와야 함
        // 여기서는 간단히 추정
        return Math.random() * 60 + 30; // 30~90% 범위 (예시)
    }
    
    /**
     * 메트릭 윈도우 (시간 윈도우 내의 메트릭 수집)
     */
    private static class MetricWindow {
        private final AtomicLong requestCount = new AtomicLong(0);
        private final AtomicLong errorCount = new AtomicLong(0);
        private final AtomicLong totalResponseTime = new AtomicLong(0);
        private final List<Long> responseTimes = new java.util.concurrent.CopyOnWriteArrayList<>();
        private long windowStartTime = System.currentTimeMillis();
        
        public void recordRequest(long responseTime, boolean success) {
            requestCount.incrementAndGet();
            totalResponseTime.addAndGet(responseTime);
            responseTimes.add(responseTime);
            
            if (!success) {
                errorCount.incrementAndGet();
            }
        }
        
        public long getRequestCount() {
            return requestCount.get();
        }
        
        public long getErrorCount() {
            return errorCount.get();
        }
        
        public double calculateTPS() {
            long elapsedSeconds = (System.currentTimeMillis() - windowStartTime) / 1000;
            if (elapsedSeconds == 0) {
                return 0.0;
            }
            return requestCount.get() / (double) elapsedSeconds;
        }
        
        public double calculateAvgLatency() {
            if (requestCount.get() == 0) {
                return 0.0;
            }
            return totalResponseTime.get() / (double) requestCount.get();
        }
        
        public double calculateP95Latency() {
            if (responseTimes.isEmpty()) {
                return 0.0;
            }
            List<Long> sorted = responseTimes.stream()
                    .sorted()
                    .collect(Collectors.toList());
            int index = (int) (sorted.size() * 0.95);
            return sorted.get(Math.min(index, sorted.size() - 1));
        }
        
        public double calculateP99Latency() {
            if (responseTimes.isEmpty()) {
                return 0.0;
            }
            List<Long> sorted = responseTimes.stream()
                    .sorted()
                    .collect(Collectors.toList());
            int index = (int) (sorted.size() * 0.99);
            return sorted.get(Math.min(index, sorted.size() - 1));
        }
        
        public double calculateErrorRate() {
            if (requestCount.get() == 0) {
                return 0.0;
            }
            return (errorCount.get() / (double) requestCount.get()) * 100.0;
        }
        
        public void reset() {
            requestCount.set(0);
            errorCount.set(0);
            totalResponseTime.set(0);
            responseTimes.clear();
            windowStartTime = System.currentTimeMillis();
        }
    }
}
