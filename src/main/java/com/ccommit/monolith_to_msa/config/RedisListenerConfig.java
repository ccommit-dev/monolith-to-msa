package com.ccommit.monolith_to_msa.config;

import com.ccommit.monolith_to_msa.service.event.OrderCreatedEventListener;
import com.ccommit.monolith_to_msa.service.event.PaymentCompletedEventListener;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * Redis Pub/Sub 리스너 등록 설정
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class RedisListenerConfig {
    
    private final OrderCreatedEventListener orderCreatedEventListener;
    private final PaymentCompletedEventListener paymentCompletedEventListener;
    private final RedisMessageListenerContainer redisMessageListenerContainer;
    
    @PostConstruct
    public void registerListeners() {
        log.info("Redis Pub/Sub 리스너 등록 시작");
        
        // 주문 생성 이벤트 리스너 등록
        redisMessageListenerContainer.addMessageListener(
                new MessageListenerAdapter(orderCreatedEventListener, "onMessage"),
                new ChannelTopic(RedisPubSubConfig.ORDER_CREATED_CHANNEL)
        );
        log.info("주문 생성 이벤트 리스너 등록 완료: 채널={}", RedisPubSubConfig.ORDER_CREATED_CHANNEL);
        
        // 결제 완료 이벤트 리스너 등록
        redisMessageListenerContainer.addMessageListener(
                new MessageListenerAdapter(paymentCompletedEventListener, "onMessage"),
                new ChannelTopic(RedisPubSubConfig.PAYMENT_COMPLETED_CHANNEL)
        );
        log.info("결제 완료 이벤트 리스너 등록 완료: 채널={}", RedisPubSubConfig.PAYMENT_COMPLETED_CHANNEL);
        
        // 컨테이너 시작
        redisMessageListenerContainer.start();
        log.info("Redis Pub/Sub 리스너 컨테이너 시작 완료");
    }
}
