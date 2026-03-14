package com.ccommit.monolith_to_msa.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 메트릭 수집 및 AI 이상 탐지 스케줄링 설정
 */
@Configuration
@EnableScheduling
public class MetricsConfig {
    // 스케줄링 활성화
    // MetricCollectorService와 AnomalyDetectionService의 @Scheduled 메서드 실행
}
