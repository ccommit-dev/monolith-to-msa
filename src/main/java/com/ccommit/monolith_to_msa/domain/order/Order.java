package com.ccommit.monolith_to_msa.domain.order;

import com.ccommit.monolith_to_msa.domain.payment.Payment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String customerId;

    @Column(nullable = false)
    private String productId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false)
    private Long totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Payment> payments = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Builder
    public Order(String customerId, String productId, Integer quantity, Long totalPrice, OrderStatus status) {
        this.customerId = customerId;
        this.productId = productId;
        this.quantity = quantity;
        this.totalPrice = totalPrice;
        this.status = status != null ? status : OrderStatus.PENDING;
    }

    public void updateStatus(OrderStatus status) {
        this.status = status;
    }

    public void cancel() {
        if (this.status == OrderStatus.DELIVERED) {
            throw new IllegalStateException("배송 완료된 주문은 취소할 수 없습니다");
        }
        this.status = OrderStatus.CANCELLED;
    }

    public void addPayment(Payment payment) {
        this.payments.add(payment);
        payment.setOrder(this);
    }

    public Long getTotalPaymentAmount() {
        return payments.stream()
                .filter(Payment::isCompleted)
                .mapToLong(Payment::getAmount)
                .sum();
    }

    public boolean isFullyPaid() {
        return getTotalPaymentAmount() >= totalPrice;
    }
}

