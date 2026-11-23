CREATE TABLE knowledge_document (
  id CHAR(36) PRIMARY KEY, title VARCHAR(140) NOT NULL, source VARCHAR(180) NOT NULL,
  uploaded_by BIGINT, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (uploaded_by) REFERENCES account(id)
);
CREATE TABLE knowledge_chunk (
  id CHAR(36) PRIMARY KEY, document_id CHAR(36) NOT NULL, ordinal INT NOT NULL, content TEXT NOT NULL,
  UNIQUE (document_id,ordinal), FOREIGN KEY (document_id) REFERENCES knowledge_document(id)
);
