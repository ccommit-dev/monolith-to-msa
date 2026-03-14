"""
Ch06.12: 성능 비교 테스트 스크립트
- Before (모놀리식) vs After (MSA + 비동기 + 캐싱 + Circuit Breaker)
- TPS 측정 및 응답 시간 비교
- 성능 개선 효과 검증
"""

from locust import HttpUser, task, between, SequentialTaskSet
import random
import time
import json


class ProductTaskSet(SequentialTaskSet):
    """상품 조회 시나리오 (캐싱 효과 측정)"""
    
    def on_start(self):
        """시나리오 시작 시 초기화"""
        self.product_ids = ["product-001", "product-002", "product-003"]
        self.current_product_id = random.choice(self.product_ids)
    
    @task(3)
    def get_product(self):
        """상품 조회 (가중치: 3) - 캐싱 효과 측정"""
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
        """재고 조회 (가중치: 2) - 캐싱 효과 측정"""
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
    """주문 생성 시나리오 (비동기 처리 효과 측정)"""
    
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
        """주문 생성 - 비동기 처리로 응답 시간 단축"""
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
            "paymentMethod": "CREDIT_CARD"
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
                response.failure("서버 오류")
            else:
                response.failure(f"예상치 못한 상태 코드: {response.status_code}")


class PerformanceComparisonUser(HttpUser):
    """
    성능 비교 부하 테스트 사용자 클래스
    - wait_time: 각 요청 사이 대기 시간 (0.5~1.5초 랜덤)
    - tasks: 실행할 작업 세트
    """
    
    wait_time = between(0.5, 1.5)  # 0.5~1.5초 랜덤 대기
    
    tasks = {
        ProductTaskSet: 3,  # 상품 조회 시나리오 (가중치: 3) - 캐싱 효과
        OrderTaskSet: 5,     # 주문 생성 시나리오 (가중치: 5) - 비동기 효과
    }
    
    def on_start(self):
        """사용자 시작 시 초기화"""
        pass
    
    def on_stop(self):
        """사용자 종료 시 정리"""
        pass
