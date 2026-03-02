package com.ccommit.monolith_to_msa.exception;

public class InsufficientStockException extends OrderException {
    
    public InsufficientStockException(String productId, Integer requested, Integer available) {
        super(String.format("재고가 부족합니다. 상품: %s, 요청 수량: %d, 현재 재고: %d", 
                productId, requested, available));
    }
}

