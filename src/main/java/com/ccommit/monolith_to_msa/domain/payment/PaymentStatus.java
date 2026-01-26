package com.ccommit.monolith_to_msa.domain.payment;

public enum PaymentStatus {
    PENDING,    // 대기 중
    COMPLETED,  // 완료
    FAILED,     // 실패
    REFUNDED    // 환불됨
}

