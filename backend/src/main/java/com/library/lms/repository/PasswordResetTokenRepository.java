package com.library.lms.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.entity.PasswordResetToken;

import jakarta.persistence.LockModeType;

/**
 * Data-access layer for {@link PasswordResetToken}.
 *
 * <p>Every lookup is by hash or by account - never by the token itself, which
 * the server does not have.</p>
 */
@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    /**
     * The stored token with this hash, locked for the rest of the transaction.
     *
     * <p><b>Locked because a token works once.</b> Two requests presenting the
     * same token at the same moment would otherwise both find it unused and both
     * reset the password. With the row locked the second waits, then finds it
     * used - and is refused.</p>
     *
     * @param tokenHash SHA-256 of the presented token, as lowercase hex
     * @return the stored token, or empty if no token has this hash
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * An account's tokens that have not been used or superseded - normally at
     * most one.
     *
     * @param userId the account
     * @return its tokens with no {@code usedAt}
     */
    List<PasswordResetToken> findByUserIdAndUsedAtIsNull(Long userId);

    /**
     * Deletes up to {@code batchSize} tokens that stopped working before the
     * cutoff: expired before it, or used or superseded before it.
     *
     * <p><b>A token that can still be redeemed never matches.</b> It has no
     * {@code used_at}, and its {@code expires_at} is in the future - after any
     * cutoff, which always lies in the past.</p>
     *
     * <p><b>Capped, and one transaction per call</b>, as the refresh-token
     * sweep is, so clearing a backlog never holds a huge number of row locks at
     * once.</p>
     *
     * @param cutoff    tokens that stopped working before this are removed
     * @param batchSize the most rows one call may delete
     * @return how many rows this call deleted
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "DELETE FROM password_reset_tokens WHERE expires_at < :cutoff OR used_at < :cutoff"
            + " LIMIT :batchSize", nativeQuery = true)
    int deleteSpentBefore(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);
}
