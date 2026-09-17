-- ==========================================================================
--  V2 - fine payment tracking
-- ==========================================================================
--  Whether a loan's fine has been paid, kept apart from how much it is: a
--  payment state, when a payment was recorded, and which member of staff
--  recorded it. The application processes no payments - there is no gateway -
--  so these columns hold what staff report.
--
--  All three are nullable, and existing rows are left as they are. A loan
--  still out has no payment state yet, and a loan returned before this
--  migration has its state worked out from its fine whenever it is read, so
--  nothing needs backfilling.
--
--  The foreign key and its index carry the name Hibernate derives from the
--  mapping, as in V1. The payment states are listed in declaration order.
-- ==========================================================================

ALTER TABLE transactions
    ADD COLUMN fine_payment_status      ENUM('NOT_REQUIRED', 'UNPAID', 'PAID') NULL,
    ADD COLUMN fine_paid_at             DATETIME(6) NULL,
    ADD COLUMN fine_payment_recorded_by BIGINT NULL,
    ADD KEY FK97podswk8lbej281xh0vw7ajt (fine_payment_recorded_by),
    ADD CONSTRAINT FK97podswk8lbej281xh0vw7ajt FOREIGN KEY (fine_payment_recorded_by) REFERENCES users (id);
