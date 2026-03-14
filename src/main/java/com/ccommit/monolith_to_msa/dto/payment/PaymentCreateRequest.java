package com.ccommit.monolith_to_msa.dto.payment;

import com.ccommit.monolith_to_msa.domain.payment.PaymentMethod;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Builder
public class PaymentCreateRequest {
    
    @NotNull(message = "주문 ID는 필수입니다")
    private Long orderId;
    
    @NotNull(message = "결제 금액은 필수입니다")
    @Min(value = 1, message = "결제 금액은 1원 이상이어야 합니다")
    private Long amount;
    
    @NotNull(message = "결제 수단은 필수입니다")
    private PaymentMethod method;
}

