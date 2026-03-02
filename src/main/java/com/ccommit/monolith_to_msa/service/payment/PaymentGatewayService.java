package com.ccommit.monolith_to_msa.service.payment;

import com.ccommit.monolith_to_msa.domain.payment.PaymentMethod;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * PG 결제 게이트웨이 Mock 서비스
 * 실제 PG사 API 호출을 시뮬레이션
 */
@Service
@Slf4j
public class PaymentGatewayService {
    
    private final Random random = new Random();
    
    /**
     * PG사에 결제 요청
     * @param amount 결제 금액
     * @param method 결제 수단
     * @return 거래 ID
     * @throws PaymentGatewayException PG사 오류 시 예외 발생
     */
    public String requestPayment(Long amount, PaymentMethod method) throws PaymentGatewayException {
        log.info("PG사 결제 요청: 금액={}, 수단={}", amount, method);
        
        // 네트워크 지연 시뮬레이션 (0.5~2초)
        simulateNetworkDelay();
        
        // 10% 확률로 실패 (타임아웃 또는 오류)
        if (random.nextInt(10) == 0) {
            log.error("PG사 결제 실패: 네트워크 오류 또는 타임아웃");
            throw new PaymentGatewayException("PG사 결제 요청 실패: 네트워크 오류");
        }
        
        // 거래 ID 생성
        String transactionId = "TXN-" + System.currentTimeMillis() + "-" + random.nextInt(1000);
        log.info("PG사 결제 성공: 거래ID={}", transactionId);
        
        return transactionId;
    }
    
    /**
     * PG사에 환불 요청
     * @param transactionId 거래 ID
     * @return 환불 성공 여부
     * @throws PaymentGatewayException PG사 오류 시 예외 발생
     */
    public boolean requestRefund(String transactionId) throws PaymentGatewayException {
        log.info("PG사 환불 요청: 거래ID={}", transactionId);
        
        // 네트워크 지연 시뮬레이션
        simulateNetworkDelay();
        
        // 5% 확률로 실패
        if (random.nextInt(20) == 0) {
            log.error("PG사 환불 실패: 네트워크 오류");
            throw new PaymentGatewayException("PG사 환불 요청 실패: 네트워크 오류");
        }
        
        log.info("PG사 환불 성공: 거래ID={}", transactionId);
        return true;
    }
    
    private void simulateNetworkDelay() {
        try {
            // 0.5~2초 랜덤 지연
            long delay = 500 + random.nextInt(1500);
            TimeUnit.MILLISECONDS.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentGatewayException("PG사 요청 중단됨", e);
        }
    }
}

