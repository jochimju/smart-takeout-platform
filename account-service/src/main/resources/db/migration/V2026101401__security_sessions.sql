ALTER TABLE employee MODIFY password VARCHAR(255) NOT NULL;
ALTER TABLE employee ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE revoked_token (
  jti VARCHAR(64) NOT NULL PRIMARY KEY,
  expires_at DATETIME NOT NULL,
  KEY idx_revoked_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
