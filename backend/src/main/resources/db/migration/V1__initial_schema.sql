-- ==========================================================================
--  V1 - initial schema
-- ==========================================================================
--  The schema the application's entities map, as it stood when Flyway was
--  introduced: five tables with their primary keys, unique constraints,
--  foreign keys and the indexes behind them.
--
--  Structure only, no rows. A deployment creates its first library and its
--  first administrator deliberately, not from a script shipped to every
--  environment.
--
--  Constraint and index names are the ones Hibernate derives from the
--  mappings, so a database Hibernate built from these same entities carries
--  the same names, and a later migration can refer to a constraint by name in
--  either.
--
--  Every table states its engine, character set and collation instead of
--  inheriting the server's. utf8mb4_0900_ai_ci compares case-insensitively,
--  and the unique usernames and emails depend on it: under a case-sensitive
--  collation "Admin" and "admin" would be two different accounts.
--
--  Never edit this file once it has been applied anywhere. Flyway records a
--  checksum for every applied migration and refuses to start when one has
--  changed. A schema change is a new file - V2, V3 and so on.
-- ==========================================================================


-- The tenant. Every other table points here, so it is created first.
CREATE TABLE libraries (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    name       VARCHAR(100) NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY UKo22n7ao1v8c3pu4e57qc39g38 (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- Accounts. Username and email are unique across every library, not per
-- library. The two status switches default to TRUE so a row inserted without
-- them is a usable account.
CREATE TABLE users (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    username           VARCHAR(255) NOT NULL,
    email              VARCHAR(255) NOT NULL,
    password           VARCHAR(255) NOT NULL,
    full_name          VARCHAR(255) NULL,
    role               ENUM('ROLE_ADMIN', 'ROLE_LIBRARIAN', 'ROLE_MEMBER') NOT NULL,
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    account_non_locked BOOLEAN      NOT NULL DEFAULT TRUE,
    library_id         BIGINT       NOT NULL,
    created_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY UKr43af9ap4edm43mmtq01oddj6 (username),
    UNIQUE KEY UK6dotkott2kjsp8vw4d0m25fb7 (email),
    KEY FK7e5hj79v26f8kycxia7p5so6i (library_id),
    CONSTRAINT FK7e5hj79v26f8kycxia7p5so6i FOREIGN KEY (library_id) REFERENCES libraries (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- Shelf labels, unique by name within one library. The unique key starts with
-- library_id, so it also serves the foreign key and no separate index exists.
CREATE TABLE categories (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    version    BIGINT       NULL,
    name       VARCHAR(100) NOT NULL,
    library_id BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_categories_library_name (library_id, name),
    CONSTRAINT FKcy3se810et7n45e0reg151law FOREIGN KEY (library_id) REFERENCES libraries (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- Titles held by a library, unique by ISBN within it. The category is
-- optional. As with categories, the unique key serves the library foreign key.
CREATE TABLE books (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    version          BIGINT       NULL,
    title            VARCHAR(200) NOT NULL,
    author           VARCHAR(150) NOT NULL,
    isbn             VARCHAR(20)  NOT NULL,
    total_copies     INT          NOT NULL,
    available_copies INT          NOT NULL,
    category_id      BIGINT       NULL,
    library_id       BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_books_library_isbn (library_id, isbn),
    KEY FKleqa3hhc0uhfvurq6mil47xk0 (category_id),
    CONSTRAINT FKleqa3hhc0uhfvurq6mil47xk0 FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT FK6gdvp1ko0pawgafhn1hyhsxia FOREIGN KEY (library_id) REFERENCES libraries (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- One borrowing of one book by one member.
--
-- The status values follow the declaration order of TransactionStatus, which
-- is also how the original column was documented. MySQL sorts an ENUM by each
-- value's position rather than alphabetically, so this order is exactly what
-- sorting transactions by status returns: ISSUED, RETURNED, OVERDUE.
CREATE TABLE transactions (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    version     BIGINT NULL,
    book_id     BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,
    library_id  BIGINT NOT NULL,
    issue_date  DATE   NOT NULL,
    due_date    DATE   NOT NULL,
    return_date DATE   NULL,
    fine_amount DOUBLE NULL,
    status      ENUM('ISSUED', 'RETURNED', 'OVERDUE') NOT NULL,
    PRIMARY KEY (id),
    KEY FKhwis5rd79vrejvuuuc513px7a (book_id),
    KEY FKqwv7rmvc8va8rep7piikrojds (user_id),
    KEY FKdjoy085d7583us3ekpyjnpexc (library_id),
    CONSTRAINT FKhwis5rd79vrejvuuuc513px7a FOREIGN KEY (book_id) REFERENCES books (id),
    CONSTRAINT FKqwv7rmvc8va8rep7piikrojds FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT FKdjoy085d7583us3ekpyjnpexc FOREIGN KEY (library_id) REFERENCES libraries (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
