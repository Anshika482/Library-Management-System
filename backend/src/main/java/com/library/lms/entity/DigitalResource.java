package com.library.lms.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Something a member can read or watch online: a PDF, an EPUB, a video or a
 * link, attached to a book in the catalogue.
 *
 * <p><b>A reference, never the file.</b> What is stored is a URL. Putting the
 * bytes in the database would turn every row into megabytes, make backups and
 * replication unworkable, and stream a whole file through the application to
 * serve one page of it. Where the file is hosted is a separate question this
 * table does not answer.</p>
 *
 * <p><b>Library-scoped, like everything else.</b> A resource belongs to one
 * library and is read through that library only; the book it belongs to is in
 * the same one, which the service checks before writing.</p>
 *
 * <p><b>Enabled is a switch, not a delete.</b> Staff turn a resource off when a
 * licence lapses or a link rots, and members stop seeing it at once while the
 * row and its history stay.</p>
 */
@Entity
@Table(
        name = "digital_resources",
        indexes = {
                @Index(name = "idx_digital_resources_library_book", columnList = "library_id, book_id"),
                @Index(name = "idx_digital_resources_library_enabled", columnList = "library_id, enabled")
        })
@Getter
@Setter
@ToString
@NoArgsConstructor
public class DigitalResource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Optimistic locking, as on every other row two members of staff could edit at once. */
    @Version
    private Long version;

    /** The library this belongs to. LAZY and out of {@code toString()}, as everywhere. */
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "library_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_digital_resources_library"))
    private Library library;

    /** The book this belongs to, always in the same library. */
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "book_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_digital_resources_book"))
    private Book book;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false)
    private ResourceType resourceType;

    /** Where the file or page is. Long enough for a signed URL from a host later. */
    @Column(name = "resource_url", nullable = false, length = 2048)
    private String resourceUrl;

    /** Whether members may see it. Staff see it either way. */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
