package com.ccommit.monolith_to_msa.service.persistence;

import com.ccommit.monolith_to_msa.domain.order.Order;
import com.ccommit.monolith_to_msa.domain.order.OrderStatus;
import com.ccommit.monolith_to_msa.domain.payment.Payment;
import com.ccommit.monolith_to_msa.domain.payment.PaymentMethod;
import com.ccommit.monolith_to_msa.domain.payment.PaymentStatus;
import com.ccommit.monolith_to_msa.repository.order.OrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 영속성 컨텍스트 예제
 * 1차 캐시, 변경 감지, 쓰기 지연 동작 예시
 */
@Service
@RequiredArgsConstructor
public class PersistenceContextExample {

    private final OrderRepository orderRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 1차 캐시 예제
     * 같은 엔티티를 두 번 조회하면 두 번째는 DB 조회 없이 1차 캐시에서 반환
     */
    @Transactional(readOnly = true)
    public void firstLevelCacheExample() {
        // 첫 번째 조회: DB에서 조회 후 1차 캐시에 저장
        Order order1 = orderRepository.findById(1L).orElseThrow();
        
        // 두 번째 조회: 1차 캐시에서 반환 (DB 조회 없음)
        Order order2 = orderRepository.findById(1L).orElseThrow();
        
        // 같은 인스턴스 참조 (동일성 보장)
        System.out.println("Same instance: " + (order1 == order2));
    }

    /**
     * 변경 감지 (Dirty Checking) 예제
     * 엔티티를 수정하면 트랜잭션 커밋 시 자동으로 UPDATE 쿼리 실행
     */
    @Transactional
    public void dirtyCheckingExample(Long orderId) {
        // 엔티티 조회 (영속 상태)
        Order order = orderRepository.findById(orderId).orElseThrow();
        
        // 엔티티 수정 (변경 감지)
        order.updateStatus(OrderStatus.CONFIRMED);
        
        // 명시적 save() 호출 불필요
        // 트랜잭션 커밋 시 자동으로 UPDATE 쿼리 실행
    }

    /**
     * 쓰기 지연 (Write Behind) 예제
     * 여러 엔티티를 저장해도 트랜잭션 커밋 시점에 한 번에 INSERT 실행
     */
    @Transactional
    public void writeBehindExample() {
        // 엔티티 생성 (비영속 상태)
        Order order1 = Order.builder()
                .customerId("customer-001")
                .productId("product-001")
                .quantity(1)
                .totalPrice(10000L)
                .build();

        Order order2 = Order.builder()
                .customerId("customer-002")
                .productId("product-002")
                .quantity(2)
                .totalPrice(20000L)
                .build();

        // 저장 (쓰기 지연 SQL 저장소에 저장)
        orderRepository.save(order1);
        orderRepository.save(order2);
        
        // 아직 DB에 INSERT되지 않음
        // 트랜잭션 커밋 시점에 한 번에 실행
    }

    /**
     * 연관관계 영속성 전이 (Cascade) 예제
     * Order를 저장하면 연관된 Payment도 함께 저장
     */
    @Transactional
    public void cascadeExample() {
        Order order = Order.builder()
                .customerId("customer-001")
                .productId("product-001")
                .quantity(1)
                .totalPrice(10000L)
                .build();

        Payment payment = Payment.builder()
                .order(order)
                .amount(10000L)
                .method(PaymentMethod.CREDIT_CARD)
                .status(PaymentStatus.PENDING)
                .build();

        // Order에 Payment 추가
        order.addPayment(payment);

        // Order만 저장해도 Payment도 함께 저장 (CASCADE)
        orderRepository.save(order);
    }

    /**
     * 영속성 컨텍스트 플러시 예제
     * flush()를 호출하면 쓰기 지연 SQL 저장소의 쿼리를 즉시 실행
     */
    @Transactional
    public void flushExample() {
        Order order = Order.builder()
                .customerId("customer-001")
                .productId("product-001")
                .quantity(1)
                .totalPrice(10000L)
                .build();

        orderRepository.save(order);

        // 영속성 컨텍스트 플러시 (즉시 DB에 반영)
        entityManager.flush();

        // 이 시점에 이미 DB에 INSERT됨
    }

    /**
     * 준영속 상태 예제
     * detach()를 호출하면 엔티티가 영속성 컨텍스트에서 분리됨
     */
    @Transactional
    public void detachExample(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        
        // 준영속 상태로 변경 (변경 감지 안 됨)
        entityManager.detach(order);
        
        // 수정해도 UPDATE 쿼리 실행 안 됨
        order.updateStatus(OrderStatus.CANCELLED);
    }
}

