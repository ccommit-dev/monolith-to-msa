package com.ccommit.monolith_to_msa.exception;

public class ProductNotFoundException extends OrderException {
    
    public ProductNotFoundException(String productId) {
        super("상품을 찾을 수 없습니다: " + productId);
    }
}

