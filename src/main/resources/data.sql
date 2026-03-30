-- 초기 상품 데이터 삽입
-- ddl-auto: create-drop이므로 매번 새로 생성되므로 중복 체크 불필요
-- 재고는 Locust 등 부하 테스트에서 빠르게 소진되지 않도록 크게 둠 (특히 product-003은 과거 30으로 먼저 고갈됨)
INSERT INTO products (product_id, name, price, stock, created_at, updated_at) 
VALUES 
  ('product-001', '테스트 상품 1', 10000, 500000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('product-002', '테스트 상품 2', 20000, 500000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('product-003', '테스트 상품 3', 30000, 500000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

