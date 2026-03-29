package com.ccommit.monolith_to_msa.service.event;

import com.ccommit.monolith_to_msa.domain.event.DeadLetterMessage;
import com.ccommit.monolith_to_msa.domain.event.PaymentCompletedEvent;
import com.ccommit.monolith_to_msa.domain.order.Order;
import com.ccommit.monolith_to_msa.domain.order.OrderStatus;
import com.ccommit.monolith_to_msa.domain.payment.PaymentStatus;
import com.ccommit.monolith_to_msa.repository.event.DeadLetterMessageRepository;
import com.ccommit.monolith_to_msa.repository.order.OrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.PrintWriter;
import java.io.StringWriter;

import static com.ccommit.monolith_to_msa.config.RedisPubSubConfig.PAYMENT_COMPLETED_CHANNEL;

/**
 * 결제 완료 이벤트 리스너 (비동기 Consumer)
 * Redis Pub/Sub을 통해 결제 완료 이벤트를 구독하고 주문 상태를 업데이트
 */
@Component
@Slf4j
public class PaymentCompletedEventListener implements MessageListener {
    
    private final OrderRepository orderRepository;
    private final DeadLetterMessageRepository dlqRepository;
    private final ObjectMapper objectMapper;
    
    public PaymentCompletedEventListener(
            OrderRepository orderRepository,
            DeadLetterMessageRepository dlqRepository) {
        this.orderRepository = orderRepository;
        this.dlqRepository = dlqRepository;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    @Transactional
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel());
        String body = new String(message.getBody());
        
        log.info("이벤트 수신: 채널={}, 메시지={}", channel, body);
        
        if (!PAYMENT_COMPLETED_CHANNEL.equals(channel)) {
            log.warn("알 수 없는 채널: {}", channel);
            return;
        }
        
        try {
            // JSON 메시지를 PaymentCompletedEvent로 변환
            PaymentCompletedEvent event = objectMapper.readValue(body, PaymentCompletedEvent.class);
            log.info("결제 완료 이벤트 처리 시작: 결제ID={}, 주문ID={}", event.getPaymentId(), event.getOrderId());
            
            // 주문 상태 업데이트
            updateOrderStatus(event);
            
        } catch (Exception e) {
            log.error("결제 완료 이벤트 처리 실패: 메시지={}, 오류={}", body, e.getMessage(), e);
            
            // DLQ에 저장
            saveToDLQ(PAYMENT_COMPLETED_CHANNEL, body, e);
        }
    }
    
    /**
     * 주문 상태 업데이트
     */
    private void updateOrderStatus(PaymentCompletedEvent event) {
        Order order = orderRepository.findById(event.getOrderId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "주문을 찾을 수 없습니다: " + event.getOrderId()));
        
        if (PaymentStatus.COMPLETED.name().equals(event.getStatus())) {
            // 결제 완료: 주문 상태를 CONFIRMED로 변경
            order.updateStatus(OrderStatus.CONFIRMED);
            orderRepository.save(order);
            log.info("주문 상태 업데이트 완료: 주문ID={}, 상태=CONFIRMED", event.getOrderId());
        } else if (PaymentStatus.FAILED.name().equals(event.getStatus())) {
            // 결제 실패: 주문 취소
            order.cancel();
            orderRepository.save(order);
            log.info("주문 취소 완료: 주문ID={}, 상태=CANCELLED", event.getOrderId());
        }
    }
    
    /**
     * DLQ에 메시지 저장
     */
    private void saveToDLQ(String channel, String message, Exception error) {
        try {
            String errorMessage = error.getMessage();
            String stackTrace = getStackTrace(error);
            
            DeadLetterMessage dlqMessage = DeadLetterMessage.builder()
                    .channel(channel)
                    .message(message)
                    .errorMessage(errorMessage)
                    .stackTrace(stackTrace)
                    .retryCount(0)
                    .status(DeadLetterMessage.MessageStatus.PENDING)
                    .build();
            
            dlqRepository.save(dlqMessage);
            log.info("DLQ에 메시지 저장: 채널={}, 메시지ID={}", channel, dlqMessage.getId());
            
        } catch (Exception e) {
            log.error("DLQ 저장 실패: 채널={}, 오류={}", channel, e.getMessage(), e);
        }
    }
    
    private String getStackTrace(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }
}
