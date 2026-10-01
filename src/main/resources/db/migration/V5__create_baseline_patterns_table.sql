-- Baseline 패턴 테이블 생성
CREATE TABLE IF NOT EXISTS baseline_patterns (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    endpoint VARCHAR(255) NOT NULL,
    learned_at TIMESTAMP NOT NULL,
    
    -- TPS 기준선
    baseline_tps DOUBLE NOT NULL,
    baseline_tps_std_dev DOUBLE NOT NULL,
    baseline_tps_min DOUBLE NOT NULL,
    baseline_tps_max DOUBLE NOT NULL,
    
    -- Latency 기준선
    baseline_latency DOUBLE NOT NULL,
    baseline_latency_std_dev DOUBLE NOT NULL,
    baseline_latency_min DOUBLE NOT NULL,
    baseline_latency_max DOUBLE NOT NULL,
    
    -- Error Rate 기준선
    baseline_error_rate DOUBLE NOT NULL,
    baseline_error_rate_std_dev DOUBLE NOT NULL,
    
    -- Resource 기준선
    baseline_cpu_usage DOUBLE NOT NULL,
    baseline_cpu_usage_std_dev DOUBLE NOT NULL,
    baseline_memory_usage DOUBLE NOT NULL,
    baseline_memory_usage_std_dev DOUBLE NOT NULL,
    baseline_connection_pool_usage DOUBLE NOT NULL,
    baseline_connection_pool_usage_std_dev DOUBLE NOT NULL,
    
    -- 학습 데이터 통계
    sample_count BIGINT NOT NULL,
    learning_start_time TIMESTAMP NOT NULL,
    learning_end_time TIMESTAMP NOT NULL,
    
    -- False Positive 최소화를 위한 임계값
    anomaly_threshold DOUBLE NOT NULL DEFAULT 0.7,
    confidence_level DOUBLE NOT NULL DEFAULT 0.95,
    
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_endpoint_learned_at (endpoint, learned_at)
);

-- 인덱스 생성
CREATE INDEX idx_endpoint ON baseline_patterns(endpoint);
CREATE INDEX idx_learned_at ON baseline_patterns(learned_at);
