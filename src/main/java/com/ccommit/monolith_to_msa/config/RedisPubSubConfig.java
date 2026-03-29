package com.ccommit.monolith_to_msa.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub 설정
 * <p>채널 메시지는 JSON 문자열 그대로 전달하기 위해 {@link StringRedisTemplate} 사용
 * (RedisTemplate + JSON 직렬화 시 문자열이 한 번 더 따옴표로 감싸져 Consumer 파싱이 실패할 수 있음)
 */
@Configuration
@Slf4j
public class RedisPubSubConfig {
    
    public static final String ORDER_CREATED_CHANNEL = "order:created";
    public static final String PAYMENT_COMPLETED_CHANNEL = "payment:completed";
    
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
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
