package com.ccommit.monolith_to_msa.domain.event;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Dead Letter Queue 엔티티
 * 처리 실패한 메시지를 저장하여 재처리 가능하도록 함
 */
@Entity
@Table(name = "dead_letter_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeadLetterMessage {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, length = 100)
    private String channel;  // Redis 채널명
    
    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;  // 원본 메시지 (JSON)
    
    @Column(nullable = false, length = 500)
    private String errorMessage;  // 에러 메시지
    
    @Column(columnDefinition = "TEXT")
    private String stackTrace;  // 스택 트레이스
    
    @Column(nullable = false)
    private Integer retryCount = 0;  // 재시도 횟수
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageStatus status = MessageStatus.PENDING;  // PENDING, PROCESSED, FAILED
    
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    private LocalDateTime processedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
    
    @Builder
    public DeadLetterMessage(String channel, String message, String errorMessage, 
                            String stackTrace, Integer retryCount, MessageStatus status) {
        this.channel = channel;
        this.message = message;
        this.errorMessage = errorMessage;
        this.stackTrace = stackTrace;
        this.retryCount = retryCount != null ? retryCount : 0;
        this.status = status != null ? status : MessageStatus.PENDING;
    }
    
    public void incrementRetryCount() {
        this.retryCount++;
    }
    
    public void markAsProcessed() {
        this.status = MessageStatus.PROCESSED;
        this.processedAt = LocalDateTime.now();
    }
    
    public void markAsFailed() {
        this.status = MessageStatus.FAILED;
    }
    
    public enum MessageStatus {
        PENDING,    // 재처리 대기
        PROCESSED,  // 재처리 완료
        FAILED      // 재처리 실패 (최대 재시도 초과)
    }
}
