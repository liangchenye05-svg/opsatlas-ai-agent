CREATE TABLE region (id BIGINT PRIMARY KEY, name VARCHAR(80) NOT NULL);
CREATE TABLE store (
  id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL, region_id BIGINT NOT NULL,
  CONSTRAINT fk_store_region FOREIGN KEY (region_id) REFERENCES region(id)
);
CREATE TABLE account (
  id BIGINT PRIMARY KEY, username VARCHAR(64) NOT NULL UNIQUE, display_name VARCHAR(80) NOT NULL,
  role_code ENUM('HEADQUARTERS','REGION','STORE') NOT NULL, password_hash VARCHAR(100) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE, region_id BIGINT,
  CONSTRAINT fk_account_region FOREIGN KEY (region_id) REFERENCES region(id)
);
CREATE TABLE account_store (
  account_id BIGINT NOT NULL, store_id BIGINT NOT NULL, PRIMARY KEY (account_id, store_id),
  FOREIGN KEY (account_id) REFERENCES account(id), FOREIGN KEY (store_id) REFERENCES store(id)
);
CREATE TABLE retail_order (
  id BIGINT PRIMARY KEY, store_id BIGINT NOT NULL, ordered_at DATETIME NOT NULL, amount DECIMAL(12,2) NOT NULL,
  CHECK (amount > 0), FOREIGN KEY (store_id) REFERENCES store(id),
  INDEX idx_order_store_date (store_id, ordered_at)
);
CREATE TABLE retail_refund (
  id BIGINT PRIMARY KEY, order_id BIGINT NOT NULL, refunded_at DATETIME NOT NULL, amount DECIMAL(12,2) NOT NULL,
  reason VARCHAR(140) NOT NULL, status ENUM('APPROVED','REJECTED') NOT NULL,
  CHECK (amount > 0), FOREIGN KEY (order_id) REFERENCES retail_order(id),
  INDEX idx_refund_order_status (order_id, status, refunded_at)
);
