package com.library.lms.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One refresh token, as the server keeps it: its hash, never the token.
 *
 * <p><b>Only the hash is stored.</b> The token itself is handed to the client
 * once, when it is issued, and exists nowhere on the server after that. A copy
 * of this table is therefore no use to anyone trying to refresh a session: a
 * SHA-256 of 256 random bits cannot be turned back into the token.</p>
 *
 * <p><b>A family is a session.</b> Logging in starts one; every refresh
 * replaces the current token with a new one in the same family and marks the
 * old one revoked. At most one token in a family is live at a time, which is
 * what makes reuse detectable: presenting a revoked token means two parties
 * hold the session, and the whole family is revoked.</p>
 *
 * <p>A revoked or expired row is kept rather than deleted. It is what lets
 * reuse of an old token be recognised, and a record of when sessions ended.</p>
 */
@Entity
@Table(
        name = "refresh_tokens",
        uniqueConstraints = @UniqueConstraint(name = "uk_refresh_tokens_token_hash", columnNames = "token_hash"),
        indexes = @Index(name = "idx_refresh_tokens_family_id", columnList = "family_id"))
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The account the session belongs to.
     *
     * <p>LAZY, no cascade, and excluded from {@code toString()} for the same
     * reasons as every other association to {@link User}.</p>
     */
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_refresh_tokens_user"))
    private User user;

    /**
     * SHA-256 of the token, as lowercase hex. Unique, and what a presented token
     * is looked up by. Kept out of {@code toString()}: not the token, but no
     * business in a log either.
     */
    @ToString.Exclude
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** The session this token belongs to: a random UUID shared by every token in it. */
    @Column(name = "family_id", nullable = false, length = 36)
    private String familyId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * When the session ends. Set at login and copied to every token that
     * replaces it, so refreshing never extends a session.
     */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** When this token stopped working - replaced, logged out or revoked. Null while it is live. */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;
}
