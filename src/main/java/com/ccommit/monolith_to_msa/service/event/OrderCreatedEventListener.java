package com.ccommit.monolith_to_msa.service.event;

import com.ccommit.monolith_to_msa.client.PaymentClient;
import com.ccommit.monolith_to_msa.domain.event.DeadLetterMessage;
import com.ccommit.monolith_to_msa.domain.event.OrderCreatedEvent;
import com.ccommit.monolith_to_msa.domain.payment.PaymentMethod;
import com.ccommit.monolith_to_msa.dto.payment.PaymentCreateRequest;
import com.ccommit.monolith_to_msa.repository.event.DeadLetterMessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;

import static com.ccommit.monolith_to_msa.config.RedisPubSubConfig.ORDER_CREATED_CHANNEL;

/**
 * 주문 생성 이벤트 리스너 (비동기 Consumer)
 * Redis Pub/Sub을 통해 주문 생성 이벤트를 구독하고 결제 처리를 비동기로 수행
 */
@Component
@Slf4j
public class OrderCreatedEventListener implements MessageListener {
    
    private final PaymentClient paymentClient;
    private final DeadLetterMessageRepository dlqRepository;
    private final ObjectMapper objectMapper;
    private static final int MAX_RETRY_COUNT = 3;
    
    public OrderCreatedEventListener(
            Optional<PaymentClient> paymentClient,
            DeadLetterMessageRepository dlqRepository) {
        this.paymentClient = paymentClient.orElse(null);
        this.dlqRepository = dlqRepository;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel());
        String body = new String(message.getBody());
        
        log.info("이벤트 수신: 채널={}, 메시지={}", channel, body);
        
        if (!ORDER_CREATED_CHANNEL.equals(channel)) {
            log.warn("알 수 없는 채널: {}", channel);
            return;
        }
        
        try {
            // JSON 메시지를 OrderCreatedEvent로 변환
            OrderCreatedEvent event = objectMapper.readValue(body, OrderCreatedEvent.class);
            log.info("주문 생성 이벤트 처리 시작: 주문ID={}", event.getOrderId());
            
            // 결제 처리 (비동기)
            processPayment(event);
            
        } catch (Exception e) {
            log.error("주문 생성 이벤트 처리 실패: 메시지={}, 오류={}", body, e.getMessage(), e);
            
            // DLQ에 저장
            saveToDLQ(ORDER_CREATED_CHANNEL, body, e);
        }
    }
    
    /**
     * 결제 처리 (비동기)
     */
    private void processPayment(OrderCreatedEvent event) {
        if (paymentClient == null) {
            log.warn("PaymentClient가 없습니다. 결제 처리를 건너뜁니다: 주문ID={}", event.getOrderId());
            return;
        }
        
        try {
            // String을 PaymentMethod enum으로 변환
            PaymentMethod paymentMethod = event.getPaymentMethod() != null 
                    ? PaymentMethod.valueOf(event.getPaymentMethod()) 
                    : PaymentMethod.CREDIT_CARD;
            
            PaymentCreateRequest paymentRequest = PaymentCreateRequest.builder()
                    .orderId(event.getOrderId())
                    .amount(event.getTotalPrice())
                    .method(paymentMethod)
                    .build();
            
            // 비동기 결제 처리 (Non-blocking)
            paymentClient.processPayment(paymentRequest)
                    .subscribe(
                            paymentResponse -> {
                                log.info("비동기 결제 처리 완료: 주문ID={}, 결제ID={}, 상태={}", 
                                        event.getOrderId(), paymentResponse.getId(), paymentResponse.getStatus());
                            },
                            error -> {
                                log.error("비동기 결제 처리 실패: 주문ID={}, 오류={}", 
                                        event.getOrderId(), error.getMessage(), error);
                            }
                    );
            
        } catch (Exception e) {
            log.error("결제 처리 중 오류 발생: 주문ID={}, 오류={}", event.getOrderId(), e.getMessage(), e);
            throw e;
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
