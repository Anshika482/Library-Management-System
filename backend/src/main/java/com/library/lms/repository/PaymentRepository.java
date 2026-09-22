package com.library.lms.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.library.lms.entity.Payment;
import com.library.lms.entity.PaymentStatus;

/**
 * Data access for {@link Payment}.
 *
 * <p>Every finder names the library, so a payment of one library is never
 * reachable from another - the same rule the loan, book and account
 * repositories follow.</p>
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** One order of a library, by the provider's reference. */
    Optional<Payment> findByLibraryIdAndProviderOrderId(Long libraryId, String providerOrderId);

    /** Whether this loan already has a payment in a given state - how a second success is refused. */
    boolean existsByTransactionIdAndStatus(Long transactionId, PaymentStatus status);

    /** An open order for a loan, so asking twice reopens rather than piles up. */
    Optional<Payment> findFirstByTransactionIdAndStatusOrderByIdDesc(Long transactionId, PaymentStatus status);
}
