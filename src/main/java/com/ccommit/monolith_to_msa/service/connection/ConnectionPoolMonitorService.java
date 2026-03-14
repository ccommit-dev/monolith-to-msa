package com.ccommit.monolith_to_msa.service.connection;

import com.ccommit.monolith_to_msa.domain.metrics.TrafficMetric;
import com.ccommit.monolith_to_msa.repository.metrics.TrafficMetricRepository;
import com.ccommit.monolith_to_msa.service.alert.AlertService;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 커넥션 풀 모니터링 서비스
 * - Part 2/3 병목 자동 감지
 * - 커넥션 풀 고갈 감지
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectionPoolMonitorService {
    
    private final DataSource dataSource;
    private final TrafficMetricRepository metricRepository;
    private final AlertService alertService;
    
    private static final double CRITICAL_THRESHOLD = 90.0; // 90% 이상 시 위험
    private static final double WARNING_THRESHOLD = 80.0; // 80% 이상 시 경고
    
    /**
     * 커넥션 풀 상태 모니터링 (30초마다)
     */
    @Scheduled(fixedRate = 30000) // 30초마다
    public void monitorConnectionPool() {
        if (!(dataSource instanceof HikariDataSource)) {
            return;
        }
        
        HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
        HikariPoolMXBean poolBean = hikariDataSource.getHikariPoolMXBean();
        
        if (poolBean == null) {
            return;
        }
        
        int activeConnections = poolBean.getActiveConnections();
        int idleConnections = poolBean.getIdleConnections();
        int totalConnections = activeConnections + idleConnections;
        int threadsAwaitingConnection = poolBean.getThreadsAwaitingConnection();
        int maximumPoolSize = hikariDataSource.getMaximumPoolSize();
        
        double usagePercentage = (totalConnections / (double) maximumPoolSize) * 100.0;
        
        // 위험 상태 감지
        if (usagePercentage >= CRITICAL_THRESHOLD || threadsAwaitingConnection > 0) {
            log.warn("커넥션 풀 고갈 감지: 사용률={:.2f}%, 활성={}, 대기 스레드={}, 최대={}",
                    usagePercentage, activeConnections, threadsAwaitingConnection, maximumPoolSize);
            
            // 최근 메트릭 업데이트
            updateRecentMetrics(usagePercentage);
            
            // 알림 발송
            alertService.sendConnectionPoolAlert(usagePercentage, activeConnections, 
                    threadsAwaitingConnection, maximumPoolSize);
        } else if (usagePercentage >= WARNING_THRESHOLD) {
            log.warn("커넥션 풀 경고: 사용률={:.2f}%, 활성={}, 최대={}",
                    usagePercentage, activeConnections, maximumPoolSize);
        }
    }
    
    /**
     * 최근 메트릭의 커넥션 풀 사용률 업데이트
     */
    private void updateRecentMetrics(double connectionPoolUsage) {
        LocalDateTime oneMinuteAgo = LocalDateTime.now().minusMinutes(1);
        List<TrafficMetric> recentMetrics = metricRepository.findByTimestampAfter(oneMinuteAgo);
        
        for (TrafficMetric metric : recentMetrics) {
            metric.setConnectionPoolUsage(connectionPoolUsage);
            metricRepository.save(metric);
        }
    }
    
    /**
     * 커넥션 풀 상태 조회
     */
    public ConnectionPoolStatus getConnectionPoolStatus() {
        if (!(dataSource instanceof HikariDataSource)) {
            return ConnectionPoolStatus.builder()
                    .available(false)
                    .build();
        }
        
        HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
        HikariPoolMXBean poolBean = hikariDataSource.getHikariPoolMXBean();
        
        if (poolBean == null) {
            return ConnectionPoolStatus.builder()
                    .available(false)
                    .build();
        }
        
        int activeConnections = poolBean.getActiveConnections();
        int idleConnections = poolBean.getIdleConnections();
        int totalConnections = activeConnections + idleConnections;
        int threadsAwaitingConnection = poolBean.getThreadsAwaitingConnection();
        int maximumPoolSize = hikariDataSource.getMaximumPoolSize();
        double usagePercentage = (totalConnections / (double) maximumPoolSize) * 100.0;
        
        return ConnectionPoolStatus.builder()
                .available(true)
                .activeConnections(activeConnections)
                .idleConnections(idleConnections)
                .totalConnections(totalConnections)
                .maximumPoolSize(maximumPoolSize)
                .threadsAwaitingConnection(threadsAwaitingConnection)
                .usagePercentage(usagePercentage)
                .isCritical(usagePercentage >= CRITICAL_THRESHOLD || threadsAwaitingConnection > 0)
                .isWarning(usagePercentage >= WARNING_THRESHOLD)
                .build();
    }
    
    /**
     * 커넥션 풀 상태 데이터 클래스
     */
    @lombok.Data
    @lombok.Builder
    public static class ConnectionPoolStatus {
        private boolean available;
        private int activeConnections;
        private int idleConnections;
        private int totalConnections;
        private int maximumPoolSize;
        private int threadsAwaitingConnection;
        private double usagePercentage;
        private boolean isCritical;
        private boolean isWarning;
    }
}
