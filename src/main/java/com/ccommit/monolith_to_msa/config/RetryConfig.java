package com.ccommit.monolith_to_msa.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Retry 설정
 * @Retryable 어노테이션 활성화
 */
@Configuration
@EnableRetry
public class RetryConfig {
}

