package com.ccommit.monolith_to_msa.dto.payment;

import com.ccommit.monolith_to_msa.domain.order.Order;
import com.ccommit.monolith_to_msa.domain.order.OrderStatus;
import com.ccommit.monolith_to_msa.domain.payment.Payment;
import com.ccommit.monolith_to_msa.domain.payment.PaymentMethod;
import com.ccommit.monolith_to_msa.domain.payment.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentResponseTest {

    @Test
    void mapsGatewayTransactionIdAndOrderIdToTheCorrectFields() {
        Order order = Order.builder()
                .customerId("customer-001")
                .productId("product-001")
                .quantity(1)
                .totalPrice(20_000L)
                .status(OrderStatus.PENDING)
                .build();
        ReflectionTestUtils.setField(order, "id", 100L);

        Payment payment = Payment.builder()
                .order(order)
                .amount(20_000L)
                .method(PaymentMethod.CREDIT_CARD)
                .status(PaymentStatus.PENDING)
                .build();
        ReflectionTestUtils.setField(payment, "id", 200L);
        payment.complete("TXN-001");

        PaymentResponse response = PaymentResponse.from(payment);

        assertThat(response.getId()).isEqualTo(200L);
        assertThat(response.getOrderId()).isEqualTo(100L);
        assertThat(response.getTransactionId()).isEqualTo("TXN-001");
    }
}
