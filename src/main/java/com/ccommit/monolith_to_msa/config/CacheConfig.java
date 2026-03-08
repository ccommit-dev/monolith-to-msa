package com.ccommit.monolith_to_msa.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis Cache 설정
 * @EnableCaching: 캐시 기능 활성화
 * TTL 전략: 캐시별로 다른 TTL 설정
 */
@Configuration
@EnableCaching
public class CacheConfig {
    
    /**
     * CacheManager 설정
     * - 기본 TTL: 3600초 (1시간)
     * - 상품 조회 캐시: 60초
     * - 재고 조회 캐시: 300초 (5분)
     * 
     * RedisConnectionFactory가 있을 때만 Redis 캐시 사용
     */
    @Bean
    @ConditionalOnBean(RedisConnectionFactory.class)
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // DevTools restart classloader 충돌을 줄이기 위해 명시적 classloader 사용
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        JdkSerializationRedisSerializer valueSerializer = new JdkSerializationRedisSerializer(classLoader);

        // 기본 캐시 설정 (3600초)
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(3600))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        valueSerializer))
                .disableCachingNullValues();
        
        // 상품 조회 캐시 설정 (60초)
        RedisCacheConfiguration productConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(60))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        valueSerializer))
                .disableCachingNullValues();
        
        // 재고 조회 캐시 설정 (300초)
        RedisCacheConfiguration stockConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(300))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        valueSerializer))
                .disableCachingNullValues();
        
        // 캐시별 설정 매핑
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        cacheConfigurations.put("product", productConfig);
        cacheConfigurations.put("stock", stockConfig);
        
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }
}
