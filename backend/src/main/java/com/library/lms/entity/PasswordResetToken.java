package com.library.lms.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
 * One self-service password reset token, as the server keeps it: its hash,
 * never the token.
 *
 * <p><b>Only the hash is stored.</b> The token is handed to the delivery
 * boundary once, when it is issued, and exists nowhere on the server after
 * that. A copy of this table is no use to anyone trying to reset a password: a
 * SHA-256 of 256 random bits cannot be turned back into the token.</p>
 *
 * <p><b>Short-lived and single-use.</b> {@code expiresAt} is minutes after
 * issue, and {@code usedAt} is set the moment the token resets a password - or
 * when a newer request for the same account supersedes it. Either way it is
 * never accepted again.</p>
 */
@Entity
@Table(
        name = "password_reset_tokens",
        uniqueConstraints = @UniqueConstraint(name = "uk_password_reset_tokens_token_hash",
                columnNames = "token_hash"))
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The account whose password the token may reset. LAZY and out of {@code toString()}, as everywhere. */
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_password_reset_tokens_user"))
    private User user;

    /** SHA-256 of the token, as lowercase hex. Unique, and what a presented token is looked up by. */
    @ToString.Exclude
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** After this the token is refused. */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** When the token stopped working - used, or superseded by a newer one. Null while it is usable. */
    @Column(name = "used_at")
    private LocalDateTime usedAt;
}
