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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * One audited change: who did what to which record, in which library, when,
 * and whether it went through.
 *
 * <p><b>Nothing here can hold a secret.</b> Every column is an id, an enum or
 * a timestamp - there is no free text at all, so no password, hash, token or
 * address can be written into an audit row by mistake or by a careless
 * caller. That is a property of the table, not a rule callers have to
 * remember.</p>
 *
 * <p><b>Library-scoped.</b> Every event belongs to one library, the one the
 * change happened in, and is read through that library only.</p>
 *
 * <p><b>Append-only.</b> There are no setters: an event is created whole and
 * never changed, and its repository offers no way to update or delete one.
 * Actor and target are plain ids rather than foreign keys, so the record of
 * what happened does not depend on those rows staying as they were.</p>
 */
@Entity
@Table(
        name = "audit_events",
        indexes = @Index(name = "idx_audit_events_library_occurred", columnList = "library_id, occurred_at"))
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The library the change happened in. LAZY and out of {@code toString()}, as everywhere. */
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "library_id", nullable = false, foreignKey = @ForeignKey(name = "fk_audit_events_library"))
    private Library library;

    /** The account that made the change; null when nobody was signed in - startup, or a self-service reset. */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false)
    private AuditAction action;

    /** What kind of record the change was made to; null when it was refused before one was identified. */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type")
    private AuditTargetType targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false)
    private AuditOutcome outcome;

    /** When it happened; stored in UTC like every other date-time. */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    public AuditEvent(Library library, Long actorUserId, AuditAction action, AuditTargetType targetType,
            Long targetId, AuditOutcome outcome, LocalDateTime occurredAt) {
        this.library = library;
        this.actorUserId = actorUserId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.outcome = outcome;
        this.occurredAt = occurredAt;
    }
}
