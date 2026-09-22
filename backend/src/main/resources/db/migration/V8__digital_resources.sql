-- ==========================================================================
--  V8 - digital resources
-- ==========================================================================
--  Something a member can read or watch online - a PDF, an EPUB, a video or a
--  link - attached to a book in the catalogue.
--
--  A reference, never the file. resource_url points at wherever the thing is
--  hosted; no column here holds bytes, and none may be added. Storing files in
--  the database would make every backup carry them, and serving one would pull
--  a whole file through the application.
--
--  Library-scoped: a resource belongs to one library, and so does the book it
--  is attached to. Reads are by library and then by book or by whether the
--  resource is enabled, which is what the two indexes are for.
--
--  enabled is a switch rather than a delete: staff turn a resource off when a
--  licence lapses or a link rots, and the row stays.
--
--  Index and constraint names are the ones the entity declares.
-- ==========================================================================

CREATE TABLE digital_resources (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    version       BIGINT        NULL,
    library_id    BIGINT        NOT NULL,
    book_id       BIGINT        NOT NULL,
    title         VARCHAR(200)  NOT NULL,
    description   VARCHAR(1000) NULL,
    resource_type ENUM('PDF', 'EPUB', 'VIDEO', 'LINK') NOT NULL,
    resource_url  VARCHAR(2048) NOT NULL,
    enabled       BIT(1)        NOT NULL,
    created_at    DATETIME(6)   NOT NULL,
    updated_at    DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_digital_resources_library_book (library_id, book_id),
    KEY idx_digital_resources_library_enabled (library_id, enabled),
    CONSTRAINT fk_digital_resources_library FOREIGN KEY (library_id) REFERENCES libraries (id),
    CONSTRAINT fk_digital_resources_book FOREIGN KEY (book_id) REFERENCES books (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
