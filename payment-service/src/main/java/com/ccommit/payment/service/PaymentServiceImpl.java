package com.ccommit.payment.service;

import com.ccommit.payment.domain.Payment;
import com.ccommit.payment.domain.PaymentStatus;
import com.ccommit.payment.dto.PaymentCreateRequest;
import com.ccommit.payment.dto.PaymentResponse;
import com.ccommit.payment.exception.PaymentNotFoundException;
import com.ccommit.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;

    @Override
    @Transactional
    public PaymentResponse processPayment(PaymentCreateRequest request) {
        Payment payment = Payment.builder()
                .orderId(request.getOrderId())
                .amount(request.getAmount())
                .method(request.getMethod())
                .status(PaymentStatus.PENDING)
                .build();

        Payment savedPayment = paymentRepository.save(payment);
        savedPayment.complete("TXN-" + UUID.randomUUID());
        return PaymentResponse.from(savedPayment);
    }

    @Override
    public PaymentResponse getPayment(Long id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException("결제를 찾을 수 없습니다: " + id));
        return PaymentResponse.from(payment);
    }
}
