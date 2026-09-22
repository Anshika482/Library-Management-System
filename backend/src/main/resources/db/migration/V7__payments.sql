-- ==========================================================================
--  V7 - payments
-- ==========================================================================
--  One row per attempt to pay a loan's fine through a payment provider: who
--  started it, for which loan, how much, the provider's own two references,
--  and how it ended.
--
--  No card data. There is no column here for a number, an expiry, a CVV, a
--  holder name or a token standing in for one, and none may be added: the card
--  never reaches this application. Nor is the gateway secret stored anywhere -
--  it comes from the environment and is used to verify a signature, never
--  written down.
--
--  Both provider references are UNIQUE. That is what makes a replayed callback
--  harmless at the last level: the same order cannot be opened twice and the
--  same payment cannot be recorded twice, whatever the application above does.
--  provider_payment_id is NULL until the provider names one, and MySQL allows
--  many NULLs in a unique index, so orders still in flight do not collide.
--
--  Rows are library-scoped and read by library and loan, hence the index.
--  Index and constraint names are the ones the entity declares.
-- ==========================================================================

CREATE TABLE payments (
    id                   BIGINT         NOT NULL AUTO_INCREMENT,
    version              BIGINT         NULL,
    library_id           BIGINT         NOT NULL,
    transaction_id       BIGINT         NOT NULL,
    initiated_by_user_id BIGINT         NOT NULL,
    provider             VARCHAR(40)    NOT NULL,
    provider_order_id    VARCHAR(100)   NOT NULL,
    provider_payment_id  VARCHAR(100)   NULL,
    amount               DECIMAL(10, 2) NOT NULL,
    currency             VARCHAR(3)     NOT NULL,
    status               ENUM('CREATED', 'SUCCEEDED', 'FAILED') NOT NULL,
    created_at           DATETIME(6)    NOT NULL,
    completed_at         DATETIME(6)    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payments_provider_order (provider_order_id),
    UNIQUE KEY uk_payments_provider_payment (provider_payment_id),
    KEY idx_payments_library_transaction (library_id, transaction_id),
    CONSTRAINT fk_payments_library FOREIGN KEY (library_id) REFERENCES libraries (id),
    CONSTRAINT fk_payments_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_payments_initiated_by FOREIGN KEY (initiated_by_user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
