package com.library.lms.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

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
}
