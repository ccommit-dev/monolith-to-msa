from locust import HttpUser, task, between, TaskSet
import random

class ProductTaskSet(TaskSet):
    """상품 조회 태스크"""
    
    def on_start(self):
        """⭐ 문서 20의 초기화 추가"""
        self.product_ids = ["product-001", "product-002", "product-003"]

    @task(5)
    def get_product(self):
        product_id = random.choice(self.product_ids)
        with self.client.get(
            f"/api/products/{product_id}",
            name="/api/products/[productId]",  # ⭐ 통계 그룹화
            catch_response=True
        ) as response:
            if response.status_code == 200:
                response.success()
            else:
                response.failure(f"Failed: {response.status_code}, body={response.text[:200]}")

    @task(3)
    def get_stock(self):
        product_id = random.choice(self.product_ids)
        with self.client.get(
            f"/api/products/{product_id}/stock",
            name="/api/products/[productId]/stock",  # ⭐ 통계 그룹화
            catch_response=True
        ) as response:
            if response.status_code == 200:
                response.success()
            else:
                response.failure(f"Failed: {response.status_code}")


class OrderTaskSet(TaskSet):
    """주문 생성 태스크"""

    @task(3)
    def create_order(self):
        product_id = random.choice(["product-001", "product-002", "product-003"])
        unit_price = {"product-001": 10000, "product-002": 20000, "product-003": 30000}
        quantity = random.randint(1, 5)
        
        data = {
            "customerId": f"customer-{random.randint(1, 100):03d}",
            "productId": product_id,
            "quantity": quantity,
            "totalPrice": unit_price[product_id] * quantity
        }

        with self.client.post(
            "/api/orders",
            json=data,
            catch_response=True
        ) as response:
            if response.status_code == 201:
                response.success()
            elif response.status_code == 400:
                # ⭐ 재고 부족은 정상 응답으로 처리
                if "재고" in response.text or "부족" in response.text:
                    response.success()
                else:
                    response.failure(f"Bad request: {response.text[:200]}")
            else:
                response.failure(f"Failed: {response.status_code}")


class PaymentTaskSet(TaskSet):
    """결제 처리 태스크 (주문 생성 후 결제)"""

    @task(1)
    def process_payment(self):
        # 주문 생성
        product_id = random.choice(["product-001", "product-002", "product-003"])
        unit_price = {"product-001": 10000, "product-002": 20000, "product-003": 30000}
        quantity = random.randint(1, 5)
        total_price = unit_price[product_id] * quantity

        order_data = {
            "customerId": f"customer-{random.randint(1, 100):03d}",
            "productId": product_id,
            "quantity": quantity,
            "totalPrice": total_price,
        }

        order_response = self.client.post("/api/orders", json=order_data)
        if order_response.status_code != 201:
            return  # 주문 실패 시 결제 안 함

        try:
            order_id = order_response.json().get("id")
        except Exception:
            return

        if not order_id:
            return

        # 결제 처리
        payment_data = {
            "orderId": order_id,
            "amount": total_price,
            "method": random.choice(["CREDIT_CARD", "BANK_TRANSFER", "MOBILE_PAY"]),
        }

        with self.client.post(
            "/api/payments",
            json=payment_data,
            catch_response=True
        ) as response:
            if response.status_code in [200, 201]:
                response.success()
            else:
                response.failure(f"Failed: {response.status_code}")


class WebsiteUser(HttpUser):
    """사용자 시뮬레이션"""
    
    # ⭐ 랜덤 실행 (문서 21 방식)
    tasks = [ProductTaskSet, OrderTaskSet, PaymentTaskSet]
    
    wait_time = between(1, 3)