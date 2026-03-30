-- 트래픽 메트릭 테이블 (Flyway 사용 시). 기본 H2 실습은 JPA ddl-auto 로 동일 스키마가 생성됨.
CREATE TABLE IF NOT EXISTS traffic_metrics (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    endpoint VARCHAR(255) NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    tps DOUBLE NOT NULL,
    avg_latency DOUBLE NOT NULL,
    p95_latency DOUBLE NOT NULL,
    p99_latency DOUBLE NOT NULL,
    error_rate DOUBLE NOT NULL,
    request_count BIGINT NOT NULL,
    error_count BIGINT NOT NULL,
    cpu_usage DOUBLE NOT NULL,
    memory_usage DOUBLE NOT NULL,
    connection_pool_usage DOUBLE NOT NULL,
    is_anomaly BOOLEAN,
    anomaly_score DOUBLE,
    anomaly_reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_timestamp ON traffic_metrics(timestamp);
CREATE INDEX idx_endpoint ON traffic_metrics(endpoint);
CREATE INDEX idx_timestamp_endpoint ON traffic_metrics(timestamp, endpoint);
CREATE INDEX idx_anomaly ON traffic_metrics(is_anomaly, timestamp);
