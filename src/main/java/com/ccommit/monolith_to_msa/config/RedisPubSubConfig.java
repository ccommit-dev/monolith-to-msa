package com.ccommit.monolith_to_msa.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis Pub/Sub 설정
 */
@Configuration
@Slf4j
public class RedisPubSubConfig {
    
    public static final String ORDER_CREATED_CHANNEL = "order:created";
    public static final String PAYMENT_COMPLETED_CHANNEL = "payment:completed";
    
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // Key는 String 직렬화
        template.setKeySerializer(RedisSerializer.string());
        template.setHashKeySerializer(RedisSerializer.string());
        
        // Value는 JSON 직렬화 (RedisSerializer.json() 사용 - Spring Data Redis 3.0+)
        template.setValueSerializer(RedisSerializer.json());
        template.setHashValueSerializer(RedisSerializer.json());
        
        template.afterPropertiesSet();
        return template;
    }
    
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }
    
    @Bean
    public ChannelTopic orderCreatedTopic() {
        return new ChannelTopic(ORDER_CREATED_CHANNEL);
    }
    
    @Bean
    public ChannelTopic paymentCompletedTopic() {
        return new ChannelTopic(PAYMENT_COMPLETED_CHANNEL);
    }
}
