-- ==========================================================================
--  V4 - password reset tokens
-- ==========================================================================
--  One row per self-service password reset token ever issued. The token itself
--  is never stored: token_hash is its SHA-256, as lowercase hex, and it is
--  unique because a presented token is looked up by it.
--
--  expires_at is short - minutes, not days - and used_at is set the moment the
--  token resets a password, or when a newer request supersedes it. A token with
--  used_at set is never accepted again.
--
--  Index and constraint names are the ones the entity declares.
-- ==========================================================================

CREATE TABLE password_reset_tokens (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at    DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_password_reset_tokens_token_hash (token_hash),
    KEY fk_password_reset_tokens_user (user_id),
    CONSTRAINT fk_password_reset_tokens_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
