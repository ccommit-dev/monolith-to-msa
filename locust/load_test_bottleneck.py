"""
Locust 병목 재현 부하 테스트 스크립트
- VU 100: 정상 동작
- VU 200: 병목 현상 재현 (커넥션 풀 고갈)
- HikariCP max 10 커넥션으로 제한
"""

from locust import HttpUser, task, between, SequentialTaskSet
import random
import time


class ProductTaskSet(SequentialTaskSet):
    """상품 조회 시나리오"""
    
    def on_start(self):
        """시나리오 시작 시 초기화"""
        self.product_ids = ["product-001", "product-002", "product-003"]
        self.current_product_id = random.choice(self.product_ids)
    
    @task(3)
    def get_product(self):
        """상품 조회 (가중치: 3)"""
        with self.client.get(
            f"/api/products/{self.current_product_id}",
            name="/api/products/[productId]",
            catch_response=True
        ) as response:
            if response.status_code == 200:
                response.success()
            elif response.status_code == 404:
                response.failure("상품을 찾을 수 없습니다")
            else:
                response.failure(f"예상치 못한 상태 코드: {response.status_code}")
    
    @task(2)
    def get_stock(self):
        """재고 조회 (가중치: 2)"""
        with self.client.get(
            f"/api/products/{self.current_product_id}/stock",
            name="/api/products/[productId]/stock",
            catch_response=True
        ) as response:
            if response.status_code == 200:
                response.success()
            elif response.status_code == 404:
                response.failure("상품을 찾을 수 없습니다")
            else:
                response.failure(f"예상치 못한 상태 코드: {response.status_code}")


class OrderTaskSet(SequentialTaskSet):
    """주문 생성 시나리오 (DB 트랜잭션 집약적)"""
    
    def on_start(self):
        """시나리오 시작 시 초기화"""
        self.product_ids = ["product-001", "product-002", "product-003"]
        self.product_prices = {
            "product-001": 10000,
            "product-002": 20000,
            "product-003": 30000
        }
        self.customer_ids = [f"customer-{i:03d}" for i in range(1, 101)]
    
    @task
    def create_order(self):
        """주문 생성 (비관적 락 사용 - 커넥션 점유 시간 증가)"""
        product_id = random.choice(self.product_ids)
        customer_id = random.choice(self.customer_ids)
        quantity = random.randint(1, 5)
        unit_price = self.product_prices[product_id]
        total_price = quantity * unit_price
        
        payload = {
            "customerId": customer_id,
            "productId": product_id,
            "quantity": quantity,
            "totalPrice": total_price,
            "paymentMethod": "CREDIT_CARD",
        }
        
        with self.client.post(
            "/api/orders",
            json=payload,
            name="/api/orders",
            catch_response=True
        ) as response:
            if response.status_code == 201:
                response.success()
            elif response.status_code == 400:
                error_body = response.text
                if "재고가 부족" in error_body:
                    response.success()  # 재고 부족은 정상적인 응답
                else:
                    response.failure(f"잘못된 요청: {error_body}")
            elif response.status_code == 404:
                response.failure("상품을 찾을 수 없습니다")
            elif response.status_code == 500:
                response.failure("서버 오류 (커넥션 풀 고갈 가능성)")
            else:
                response.failure(f"예상치 못한 상태 코드: {response.status_code}")


class PaymentTaskSet(SequentialTaskSet):
    """결제 처리 시나리오 (트랜잭션 집약적)"""
    
    def on_start(self):
        """시나리오 시작 시 초기화"""
        self.order_ids = []
    
    @task
    def process_payment(self):
        """결제 처리"""
        # 주문 ID가 없으면 주문 생성 후 결제
        if not self.order_ids:
            product_id = "product-001"
            customer_id = f"customer-{random.randint(1, 100):03d}"
            quantity = 1
            total_price = 10000
            
            order_payload = {
                "customerId": customer_id,
                "productId": product_id,
                "quantity": quantity,
                "totalPrice": total_price,
                "paymentMethod": "CREDIT_CARD",
            }
            
            order_response = self.client.post("/api/orders", json=order_payload)
            if order_response.status_code == 201:
                order_data = order_response.json()
                self.order_ids.append(order_data.get("id"))
            else:
                return
        
        # 결제 처리
        if self.order_ids:
            order_id = self.order_ids[0]
            payment_payload = {
                "orderId": order_id,
                "amount": 10000,
                "method": "CREDIT_CARD"
            }
            
            with self.client.post(
                "/api/payments",
                json=payment_payload,
                name="/api/payments",
                catch_response=True
            ) as response:
                if response.status_code == 201:
                    response.success()
                    self.order_ids.clear()
                elif response.status_code == 400:
                    error_text = response.text
                    if "이미 결제" in error_text or "결제된" in error_text:
                        response.success()
                        self.order_ids.clear()
                    else:
                        response.failure(f"결제 실패: {error_text}")
                        self.order_ids.clear()
                elif response.status_code == 500:
                    response.failure("서버 오류 (커넥션 풀 고갈 가능성)")
                    self.order_ids.clear()
                else:
                    response.failure(f"예상치 못한 상태 코드: {response.status_code}")
                    self.order_ids.clear()


class BottleneckUser(HttpUser):
    """
    병목 재현 부하 테스트 사용자 클래스
    - wait_time: 각 요청 사이 대기 시간 (0.5~1.5초 랜덤)
    - tasks: 실행할 작업 세트
    """
    
    wait_time = between(0.5, 1.5)  # 0.5~1.5초 랜덤 대기 (더 빠른 요청으로 병목 유발)
    
    tasks = {
        ProductTaskSet: 3,  # 상품 조회 시나리오 (가중치: 3)
        OrderTaskSet: 5,     # 주문 생성 시나리오 (가중치: 5) - DB 집약적
        PaymentTaskSet: 2,   # 결제 처리 시나리오 (가중치: 2) - 트랜잭션 집약적
    }
    
    def on_start(self):
        """사용자 시작 시 초기화"""
        pass
    
    def on_stop(self):
        """사용자 종료 시 정리"""
        pass

