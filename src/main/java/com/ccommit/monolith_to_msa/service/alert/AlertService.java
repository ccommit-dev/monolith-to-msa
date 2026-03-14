package com.ccommit.monolith_to_msa.service.alert;

import com.ccommit.monolith_to_msa.domain.metrics.TrafficMetric;
import com.ccommit.monolith_to_msa.service.ai.EnhancedAnomalyDetectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 알림 서비스
 * - 이상치 감지 시 알림 발송
 * - 커넥션 풀 고갈 시 알림 발송
 * - 중복 알림 방지
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {
    
    // 중복 알림 방지를 위한 추적 (5분간 동일 알림 방지)
    private final ConcurrentHashMap<String, LocalDateTime> lastAlertTime = new ConcurrentHashMap<>();
    private static final int ALERT_COOLDOWN_MINUTES = 5;
    
    /**
     * 이상치 감지 알림 발송
     */
    public void sendAnomalyAlert(TrafficMetric metric, 
                                 EnhancedAnomalyDetectionService.AnomalyDetectionResult result) {
        String alertKey = String.format("anomaly:%s:%s", 
                metric.getEndpoint(), 
                metric.getTimestamp().toLocalDate());
        
        if (shouldSuppressAlert(alertKey)) {
            log.debug("알림 억제: 최근에 동일한 알림이 발송되었습니다. key={}", alertKey);
            return;
        }
        
        // 실제 알림 발송 (로그, 이메일, 슬랙 등)
        log.error("🚨 이상치 감지 알림: endpoint={}, score={:.2f}, confidence={:.2f}, reason={}",
                metric.getEndpoint(), result.score, result.confidence, result.reason);
        
        // TODO: 실제 알림 채널 연동 (이메일, 슬랙, PagerDuty 등)
        // emailService.sendAlert(...);
        // slackService.sendAlert(...);
        
        lastAlertTime.put(alertKey, LocalDateTime.now());
    }
    
    /**
     * 커넥션 풀 고갈 알림 발송
     */
    public void sendConnectionPoolAlert(double usagePercentage, int activeConnections, 
                                       int threadsAwaitingConnection, int maximumPoolSize) {
        String alertKey = "connection-pool-exhaustion";
        
        if (shouldSuppressAlert(alertKey)) {
            log.debug("알림 억제: 최근에 동일한 알림이 발송되었습니다. key={}", alertKey);
            return;
        }
        
        // 실제 알림 발송
        log.error("🚨 커넥션 풀 고갈 알림: 사용률={:.2f}%, 활성 커넥션={}, 대기 스레드={}, 최대={}",
                usagePercentage, activeConnections, threadsAwaitingConnection, maximumPoolSize);
        
        // TODO: 실제 알림 채널 연동
        // emailService.sendAlert(...);
        // slackService.sendAlert(...);
        
        lastAlertTime.put(alertKey, LocalDateTime.now());
    }
    
    /**
     * 알림 억제 여부 확인
     */
    private boolean shouldSuppressAlert(String alertKey) {
        LocalDateTime lastAlert = lastAlertTime.get(alertKey);
        if (lastAlert == null) {
            return false;
        }
        
        LocalDateTime cooldownEnd = lastAlert.plusMinutes(ALERT_COOLDOWN_MINUTES);
        return LocalDateTime.now().isBefore(cooldownEnd);
    }
    
    /**
     * 알림 통계 조회
     */
    public AlertStatistics getAlertStatistics() {
        LocalDateTime oneDayAgo = LocalDateTime.now().minusDays(1);
        long recentAlerts = lastAlertTime.values().stream()
                .filter(time -> time.isAfter(oneDayAgo))
                .count();
        
        return AlertStatistics.builder()
                .totalAlerts((int) recentAlerts)
                .lastAlertTime(lastAlertTime.values().stream()
                        .max(LocalDateTime::compareTo)
                        .orElse(null))
                .build();
    }
    
    /**
     * 알림 통계 데이터 클래스
     */
    @lombok.Data
    @lombok.Builder
    public static class AlertStatistics {
        private int totalAlerts;
        private LocalDateTime lastAlertTime;
    }
}
