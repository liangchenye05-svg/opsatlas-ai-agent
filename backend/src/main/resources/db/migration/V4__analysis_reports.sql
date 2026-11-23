CREATE TABLE analysis_session (
  id CHAR(36) PRIMARY KEY, account_id BIGINT NOT NULL, question VARCHAR(1200) NOT NULL,
  state ENUM('RUNNING','DRAFT','CONFIRMED','FAILED') NOT NULL, draft JSON, failure VARCHAR(240),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (account_id) REFERENCES account(id), INDEX idx_session_owner (account_id,created_at)
);
CREATE TABLE weekly_report (
  id CHAR(36) PRIMARY KEY, account_id BIGINT NOT NULL, analysis_id CHAR(36) NOT NULL UNIQUE,
  content JSON NOT NULL, confirmed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (account_id) REFERENCES account(id), FOREIGN KEY (analysis_id) REFERENCES analysis_session(id),
  INDEX idx_report_owner (account_id,confirmed_at)
);
CREATE TABLE operation_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, account_id BIGINT NOT NULL, action_code VARCHAR(60) NOT NULL,
  target VARCHAR(80) NOT NULL, outcome VARCHAR(24) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (account_id) REFERENCES account(id), INDEX idx_audit_owner (account_id,id)
);
