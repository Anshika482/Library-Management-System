-- ==========================================================================
--  V3 - refresh tokens
-- ==========================================================================
--  One row per refresh token ever issued. The token itself is never stored:
--  token_hash is its SHA-256, as lowercase hex, and it is unique because a
--  presented token is looked up by it.
--
--  family_id groups the tokens of one login session. Every refresh revokes the
--  current token and adds its replacement to the same family, and the whole
--  family is revoked at logout or when a used token is presented again - so
--  it is indexed.
--
--  Rows are kept once revoked or expired: an old token must still be
--  recognisable in order to be refused as reuse.
--
--  Index and constraint names are the ones the entity declares.
-- ==========================================================================

CREATE TABLE refresh_tokens (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    family_id  VARCHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_tokens_token_hash (token_hash),
    KEY idx_refresh_tokens_family_id (family_id),
    KEY fk_refresh_tokens_user (user_id),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
