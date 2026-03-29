package com.ccommit.monolith_to_msa.service.payment;

import com.ccommit.monolith_to_msa.domain.payment.Payment;
import com.ccommit.monolith_to_msa.domain.payment.PaymentStatus;
import com.ccommit.monolith_to_msa.dto.payment.PaymentCreateRequest;
import com.ccommit.monolith_to_msa.dto.payment.PaymentResponse;
import com.ccommit.monolith_to_msa.exception.OrderException;
import com.ccommit.monolith_to_msa.repository.payment.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment 서비스 구현체
 * 트랜잭션 관리 및 재시도 로직 포함
 */
@Service
@Profile("payment")
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class PaymentServiceImpl implements PaymentService {
    
    private final PaymentRepository paymentRepository;
    private final PaymentGatewayService paymentGatewayService;
    
    @Override
    @Transactional
    @Retryable(
        retryFor = PaymentGatewayException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public PaymentResponse processPayment(PaymentCreateRequest request) {
        log.info("결제 처리 시작: 주문ID={}, 금액={}", request.getOrderId(), request.getAmount());
        
        // 1. 결제 엔티티 생성 (PENDING 상태) - 별도 트랜잭션으로 저장
        Payment savedPayment = savePaymentEntity(request);
        log.info("결제 엔티티 생성: 결제ID={}, 상태={}", savedPayment.getId(), savedPayment.getStatus());
        
        try {
            // 3. PG사 결제 요청 (재시도 가능)
            String transactionId = paymentGatewayService.requestPayment(
                    request.getAmount(),
                    request.getMethod()
            );
            
            // 4. 결제 완료 처리
            Payment completedPayment = updatePaymentStatus(savedPayment.getId(), PaymentStatus.COMPLETED, transactionId);
            
            log.info("결제 완료: 결제ID={}, 거래ID={}", completedPayment.getId(), transactionId);
            return PaymentResponse.from(completedPayment);
            
        } catch (PaymentGatewayException e) {
            // 5. 결제 실패 처리 - 실패 상태로 저장
            log.error("PG사 결제 실패: 결제ID={}, 오류={}", savedPayment.getId(), e.getMessage());
            updatePaymentStatus(savedPayment.getId(), PaymentStatus.FAILED, null);
            
            // 실패한 결제도 저장되었으므로 예외를 throw
            throw new OrderException("결제 처리에 실패했습니다: " + e.getMessage(), e);
        }
    }
    
    /**
     * 결제 엔티티 저장 (별도 트랜잭션으로 저장하여 롤백 방지)
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Payment savePaymentEntity(PaymentCreateRequest request) {
        Payment payment = Payment.builder()
                .orderId(request.getOrderId())
                .amount(request.getAmount())
                .method(request.getMethod())
                .status(PaymentStatus.PENDING)
                .build();
        
        return paymentRepository.save(payment);
    }
    
    /**
     * 결제 상태 업데이트 (별도 트랜잭션으로 저장하여 롤백 방지)
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Payment updatePaymentStatus(Long paymentId, PaymentStatus status, String transactionId) {
        // detached 엔티티 문제 방지를 위해 ID로 다시 조회
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new OrderException("결제를 찾을 수 없습니다: " + paymentId));
        
        // 상태에 따라 처리
        if (status == PaymentStatus.COMPLETED && transactionId != null) {
            payment.complete(transactionId);
        } else if (status == PaymentStatus.FAILED) {
            payment.fail();
        }
        
        return paymentRepository.save(payment);
    }
    
    @Override
    public PaymentResponse getPayment(Long id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new OrderException("결제를 찾을 수 없습니다: " + id));
        return PaymentResponse.from(payment);
    }
    
    @Override
    @Transactional
    @Retryable(
        retryFor = PaymentGatewayException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public PaymentResponse refundPayment(Long paymentId) {
        log.info("환불 처리 시작: 결제ID={}", paymentId);
        
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new OrderException("결제를 찾을 수 없습니다: " + paymentId));
        
        if (!payment.isCompleted()) {
            throw new OrderException("완료된 결제만 환불할 수 있습니다");
        }
        
        try {
            // PG사 환불 요청 (재시도 가능)
            paymentGatewayService.requestRefund(payment.getTransactionId());
            
            // 환불 처리
            payment.refund();
            paymentRepository.save(payment);
            
            log.info("환불 완료: 결제ID={}", paymentId);
            return PaymentResponse.from(payment);
            
        } catch (PaymentGatewayException e) {
            log.error("PG사 환불 실패: 결제ID={}, 오류={}", paymentId, e.getMessage());
            throw new OrderException("환불 처리에 실패했습니다: " + e.getMessage(), e);
        }
    }
}

