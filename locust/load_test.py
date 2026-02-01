"""
Locust 부하 테스트 스크립트
- 상품 조회, 재고 조회, 주문 생성, 결제 처리 시나리오
- Ramp-up → Steady → Ramp-down 패턴
"""

from locust import HttpUser, task, between, SequentialTaskSet
import random
import json


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
    """주문 생성 시나리오"""
    
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
        """주문 생성"""
        product_id = random.choice(self.product_ids)
        customer_id = random.choice(self.customer_ids)
        quantity = random.randint(1, 5)
        unit_price = self.product_prices[product_id]
        total_price = quantity * unit_price
        
        payload = {
            "customerId": customer_id,
            "productId": product_id,
            "quantity": quantity,
            "totalPrice": total_price
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
                # 재고 부족은 정상적인 비즈니스 로직이므로 실패로 처리하지 않음
                error_body = response.text
                if "재고가 부족" in error_body:
                    response.success()  # 재고 부족은 정상적인 응답
                else:
                    response.failure(f"잘못된 요청: {error_body}")
            elif response.status_code == 404:
                response.failure("상품을 찾을 수 없습니다")
            else:
                response.failure(f"예상치 못한 상태 코드: {response.status_code}")


class PaymentTaskSet(SequentialTaskSet):
    """결제 처리 시나리오"""
    
    def on_start(self):
        """시나리오 시작 시 초기화"""
        self.order_ids = []  # 주문 생성 후 결제에 사용
    
    @task
    def process_payment(self):
        """결제 처리"""
        # 주문 ID가 없으면 주문 생성 후 결제
        if not self.order_ids:
            # 주문 먼저 생성
            product_id = "product-001"
            customer_id = f"customer-{random.randint(1, 100):03d}"
            quantity = 1
            total_price = 10000
            
            order_payload = {
                "customerId": customer_id,
                "productId": product_id,
                "quantity": quantity,
                "totalPrice": total_price
            }
            
            order_response = self.client.post("/api/orders", json=order_payload)
            if order_response.status_code == 201:
                order_data = order_response.json()
                self.order_ids.append(order_data.get("id"))
            else:
                # 주문 생성 실패 시 종료
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
                    # 결제 성공 시 order_ids 비워서 다음 task에서 새 주문 생성
                    self.order_ids.clear()
                elif response.status_code == 400:
                    error_text = response.text
                    # 이미 결제된 주문인 경우도 성공으로 처리하고 order_ids 비움
                    if "이미 결제" in error_text or "결제된" in error_text:
                        response.success()
                        self.order_ids.clear()
                    else:
                        response.failure(f"결제 실패: {error_text}")
                        # 결제 실패 시에도 order_ids 비워서 다음 task에서 새 주문 생성
                        self.order_ids.clear()
                else:
                    response.failure(f"예상치 못한 상태 코드: {response.status_code}")
                    # 오류 발생 시에도 order_ids 비워서 다음 task에서 새 주문 생성
                    self.order_ids.clear()


class ApiUser(HttpUser):
    """
    부하 테스트 사용자 클래스
    - wait_time: 각 요청 사이 대기 시간 (1~3초 랜덤)
    - tasks: 실행할 작업 세트
    """
    
    wait_time = between(1, 3)  # 1~3초 랜덤 대기
    
    tasks = {
        ProductTaskSet: 5,  # 상품 조회 시나리오 (가중치: 5)
        OrderTaskSet: 3,     # 주문 생성 시나리오 (가중치: 3)
        PaymentTaskSet: 2,   # 결제 처리 시나리오 (가중치: 2) - 주문 생성 후 결제하므로 적절한 비율
    }
    
    def on_start(self):
        """사용자 시작 시 초기화"""
        print(f"사용자 시작: {self.host}")
    
    def on_stop(self):
        """사용자 종료 시 정리"""
        print(f"사용자 종료: {self.host}")
