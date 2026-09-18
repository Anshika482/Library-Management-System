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

import com.library.lms.entity.RefreshToken;

import jakarta.persistence.LockModeType;

/**
 * Data-access layer for {@link RefreshToken}.
 *
 * <p>Every lookup is by hash, family or account - never by the token itself,
 * which the server does not have.</p>
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * The stored token with this hash, locked for the rest of the transaction.
     *
     * <p><b>Locked because a token works once.</b> Two requests presenting the
     * same token at the same moment would otherwise both find it live and both
     * be given a replacement, forking one session into two. With the row locked
     * the second waits, then finds the token already used - which is exactly
     * the reuse the service refuses.</p>
     *
     * @param tokenHash SHA-256 of the presented token, as lowercase hex
     * @return the stored token, or empty if no token has this hash
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * The still-live tokens of one session - normally at most one.
     *
     * @param familyId the session
     * @return its tokens that have not been revoked
     */
    List<RefreshToken> findByFamilyIdAndRevokedAtIsNull(String familyId);

    /**
     * Every still-live token of one account, across all its sessions.
     *
     * @param userId the account
     * @return its tokens that have not been revoked
     */
    List<RefreshToken> findByUserIdAndRevokedAtIsNull(Long userId);

    /**
     * Deletes up to {@code batchSize} tokens of sessions that ended before the
     * cutoff.
     *
     * <p><b>The condition is the session's end, never {@code revoked_at}.</b>
     * Every token of a session carries the same {@code expires_at}, so a token
     * that is still usable always has one in the future and can never be caught
     * by this delete - the sweep cannot end a session that is still running. A
     * token revoked at logout, by contrast, belongs to a session that may still
     * have days left, and it has to stay: without it, presenting that token
     * again would look like an unknown token rather than the reuse it is.</p>
     *
     * <p><b>Capped on purpose.</b> A backlog is cleared a batch per call rather
     * than in one statement, so no single transaction holds hundreds of
     * thousands of row locks. Each call is its own transaction, which is what
     * makes that cap meaningful.</p>
     *
     * @param cutoff    sessions that ended before this are removed
     * @param batchSize the most rows one call may delete
     * @return how many rows this call deleted
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "DELETE FROM refresh_tokens WHERE expires_at < :cutoff LIMIT :batchSize", nativeQuery = true)
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);
}
