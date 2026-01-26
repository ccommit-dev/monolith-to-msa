package com.ccommit.monolith_to_msa.dto.order;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class OrderCreateRequest {

    @NotBlank(message = "고객 ID는 필수입니다")
    private String customerId;

    @NotBlank(message = "상품 ID는 필수입니다")
    private String productId;

    @NotNull(message = "수량은 필수입니다")
    @Min(value = 1, message = "수량은 1개 이상이어야 합니다")
    private Integer quantity;

    @NotNull(message = "총 가격은 필수입니다")
    @Min(value = 0, message = "총 가격은 0원 이상이어야 합니다")
    private Long totalPrice;
}

