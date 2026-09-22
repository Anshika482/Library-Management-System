-- ==========================================================================
--  V6 - audit loan actions
-- ==========================================================================
--  Widens two ENUM columns of audit_events so the log can also record what
--  happens to loans: a book issued, a book returned, and a fine recorded as
--  paid. Those three are the library's day-to-day operations, and the fine is
--  money; until now neither left a trace of who did it.
--
--  Additive only. Both statements repeat every value the column already holds,
--  in the same order, and append the new ones - MySQL stores an ENUM as the
--  value's position, so keeping the existing ones where they are leaves every
--  stored row meaning exactly what it meant before. No row is read or written
--  by this migration, and nothing is removed or renamed.
--
--  The order here is the order the Java enums declare, as in V5.
-- ==========================================================================

ALTER TABLE audit_events
    MODIFY COLUMN action ENUM('USER_CREATED', 'USER_STATUS_CHANGED', 'PASSWORD_CHANGED', 'PASSWORD_RESET_BY_STAFF',
                              'PASSWORD_RESET_REQUESTED', 'PASSWORD_RESET_COMPLETED', 'LIBRARY_CREATED',
                              'LIBRARY_BOOTSTRAPPED', 'BOOK_ISSUED', 'BOOK_RETURNED', 'FINE_PAID') NOT NULL;

ALTER TABLE audit_events
    MODIFY COLUMN target_type ENUM('USER', 'LIBRARY', 'LOAN') NULL;
