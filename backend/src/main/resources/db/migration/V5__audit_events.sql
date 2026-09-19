-- ==========================================================================
--  V5 - audit events
-- ==========================================================================
--  One row per security-sensitive change to accounts, passwords and
--  libraries: who did what to which record, in which library, when, and
--  whether it went through.
--
--  Every column is an id, an ENUM or a timestamp. There is no free text, so no
--  password, hash, token or address can ever be written here.
--
--  Rows are library-scoped - library_id is required - and read by library and
--  time, hence the index. Actor and target are plain ids rather than foreign
--  keys, so the record of what happened does not depend on those rows.
--  occurred_at is UTC, like every other DATETIME column.
--
--  Index and constraint names are the ones the entity declares.
-- ==========================================================================

CREATE TABLE audit_events (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    library_id    BIGINT      NOT NULL,
    actor_user_id BIGINT      NULL,
    action        ENUM('USER_CREATED', 'USER_STATUS_CHANGED', 'PASSWORD_CHANGED', 'PASSWORD_RESET_BY_STAFF',
                       'PASSWORD_RESET_REQUESTED', 'PASSWORD_RESET_COMPLETED', 'LIBRARY_CREATED',
                       'LIBRARY_BOOTSTRAPPED') NOT NULL,
    target_type   ENUM('USER', 'LIBRARY') NULL,
    target_id     BIGINT      NULL,
    outcome       ENUM('SUCCESS', 'FAILURE') NOT NULL,
    occurred_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_events_library_occurred (library_id, occurred_at),
    CONSTRAINT fk_audit_events_library FOREIGN KEY (library_id) REFERENCES libraries (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
