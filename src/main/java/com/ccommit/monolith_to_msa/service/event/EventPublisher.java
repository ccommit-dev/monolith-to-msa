package com.ccommit.monolith_to_msa.service.event;

import com.ccommit.monolith_to_msa.domain.event.OrderCreatedEvent;
import com.ccommit.monolith_to_msa.domain.event.PaymentCompletedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Service;

import static com.ccommit.monolith_to_msa.config.RedisPubSubConfig.ORDER_CREATED_CHANNEL;
import static com.ccommit.monolith_to_msa.config.RedisPubSubConfig.PAYMENT_COMPLETED_CHANNEL;

/**
 * Redis Pub/Sub 이벤트 발행자
 */
@Service
@Slf4j
public class EventPublisher {
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    
    public EventPublisher(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    /**
     * 주문 생성 이벤트 발행
     */
    public void publishOrderCreated(OrderCreatedEvent event) {
        try {
            String message = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(ORDER_CREATED_CHANNEL, message);
            log.info("주문 생성 이벤트 발행: 주문ID={}, 채널={}", event.getOrderId(), ORDER_CREATED_CHANNEL);
        } catch (JsonProcessingException e) {
            log.error("주문 생성 이벤트 발행 실패: 주문ID={}, 오류={}", event.getOrderId(), e.getMessage(), e);
            throw new RuntimeException("이벤트 발행 실패", e);
        }
    }
    
    /**
     * 결제 완료 이벤트 발행
     */
    public void publishPaymentCompleted(PaymentCompletedEvent event) {
        try {
            String message = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(PAYMENT_COMPLETED_CHANNEL, message);
            log.info("결제 완료 이벤트 발행: 결제ID={}, 주문ID={}, 채널={}", 
                    event.getPaymentId(), event.getOrderId(), PAYMENT_COMPLETED_CHANNEL);
        } catch (JsonProcessingException e) {
            log.error("결제 완료 이벤트 발행 실패: 결제ID={}, 오류={}", event.getPaymentId(), e.getMessage(), e);
            throw new RuntimeException("이벤트 발행 실패", e);
        }
    }
}
