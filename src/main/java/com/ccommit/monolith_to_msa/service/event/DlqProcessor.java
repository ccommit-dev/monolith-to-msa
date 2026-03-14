package com.ccommit.monolith_to_msa.service.event;

import com.ccommit.monolith_to_msa.domain.event.DeadLetterMessage;
import com.ccommit.monolith_to_msa.domain.event.OrderCreatedEvent;
import com.ccommit.monolith_to_msa.domain.event.PaymentCompletedEvent;
import com.ccommit.monolith_to_msa.repository.event.DeadLetterMessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.ccommit.monolith_to_msa.config.RedisPubSubConfig.ORDER_CREATED_CHANNEL;
import static com.ccommit.monolith_to_msa.config.RedisPubSubConfig.PAYMENT_COMPLETED_CHANNEL;

/**
 * Dead Letter Queue 처리 서비스
 * 주기적으로 DLQ의 메시지를 재처리
 */
@Service
@Slf4j
public class DlqProcessor {
    
    private final DeadLetterMessageRepository dlqRepository;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private static final int MAX_RETRY_COUNT = 3;
    
    public DlqProcessor(
            DeadLetterMessageRepository dlqRepository,
            EventPublisher eventPublisher) {
        this.dlqRepository = dlqRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    /**
     * DLQ 메시지 재처리 (스케줄러: 1분마다 실행)
     */
    @Scheduled(fixedDelay = 60000) // 1분
    @Transactional
    public void processDlqMessages() {
        List<DeadLetterMessage> pendingMessages = dlqRepository
                .findByStatusAndRetryCountLessThan(
                        DeadLetterMessage.MessageStatus.PENDING, 
                        MAX_RETRY_COUNT);
        
        if (pendingMessages.isEmpty()) {
            return;
        }
        
        log.info("DLQ 메시지 재처리 시작: 건수={}", pendingMessages.size());
        
        for (DeadLetterMessage dlqMessage : pendingMessages) {
            try {
                retryMessage(dlqMessage);
            } catch (Exception e) {
                log.error("DLQ 메시지 재처리 실패: 메시지ID={}, 오류={}", 
                        dlqMessage.getId(), e.getMessage(), e);
                
                // 재시도 횟수 증가
                dlqMessage.incrementRetryCount();
                
                // 최대 재시도 횟수 초과 시 FAILED 상태로 변경
                if (dlqMessage.getRetryCount() >= MAX_RETRY_COUNT) {
                    dlqMessage.markAsFailed();
                    log.error("DLQ 메시지 재처리 최대 횟수 초과: 메시지ID={}, 재시도횟수={}", 
                            dlqMessage.getId(), dlqMessage.getRetryCount());
                }
                
                dlqRepository.save(dlqMessage);
            }
        }
    }
    
    /**
     * DLQ 메시지 재처리
     */
    private void retryMessage(DeadLetterMessage dlqMessage) throws Exception {
        log.info("DLQ 메시지 재처리 시도: 메시지ID={}, 채널={}, 재시도횟수={}", 
                dlqMessage.getId(), dlqMessage.getChannel(), dlqMessage.getRetryCount());
        
        String channel = dlqMessage.getChannel();
        String message = dlqMessage.getMessage();
        
        // 채널에 따라 이벤트 재발행
        if (ORDER_CREATED_CHANNEL.equals(channel)) {
            OrderCreatedEvent event = objectMapper.readValue(message, OrderCreatedEvent.class);
            eventPublisher.publishOrderCreated(event);
            
        } else if (PAYMENT_COMPLETED_CHANNEL.equals(channel)) {
            PaymentCompletedEvent event = objectMapper.readValue(message, PaymentCompletedEvent.class);
            eventPublisher.publishPaymentCompleted(event);
            
        } else {
            throw new IllegalArgumentException("알 수 없는 채널: " + channel);
        }
        
        // 재처리 성공: PROCESSED 상태로 변경
        dlqMessage.markAsProcessed();
        dlqRepository.save(dlqMessage);
        
        log.info("DLQ 메시지 재처리 성공: 메시지ID={}", dlqMessage.getId());
    }
}
