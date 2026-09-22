package com.library.lms.entity;

import java.math.BigDecimal;
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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One attempt to pay a loan's fine through a payment provider.
 *
 * <p><b>Nothing about a card is here.</b> No number, no expiry, no CVV, no
 * holder name, no token that could stand in for one. The card is handled by the
 * provider, on their pages; what this row keeps is the provider's own two
 * references, an amount, and how the attempt ended. Storing anything more would
 * put this application inside the payment card rules it exists to stay outside
 * of.</p>
 *
 * <p><b>The provider's ids are what make a repeat harmless.</b> Both are unique,
 * so the same order cannot be opened twice and the same payment cannot be
 * completed twice however many times a caller sends it. That constraint is the
 * last line of the idempotency rule, underneath the checks in
 * {@code PaymentService}.</p>
 *
 * <p><b>Library-scoped</b>, like everything else: a payment belongs to the
 * library whose loan it settles, and is read no other way.</p>
 */
@Entity
@Table(
        name = "payments",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payments_provider_order", columnNames = "provider_order_id"),
                @UniqueConstraint(name = "uk_payments_provider_payment", columnNames = "provider_payment_id")
        },
        indexes = @Index(name = "idx_payments_library_transaction", columnList = "library_id, transaction_id"))
@Getter
@Setter
@ToString
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Optimistic locking, as on every other row two requests could race for. */
    @Version
    private Long version;

    /** The library whose loan this settles. LAZY and out of {@code toString()}, as everywhere. */
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "library_id", nullable = false, foreignKey = @ForeignKey(name = "fk_payments_library"))
    private Library library;

    /** The loan whose fine is being paid. */
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false, foreignKey = @ForeignKey(name = "fk_payments_transaction"))
    private Transaction transaction;

    /** Who started the payment: the member who owes the fine, or staff acting at the desk. */
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "initiated_by_user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_payments_initiated_by"))
    private User initiatedBy;

    /** Which provider this order belongs to, so a later change of provider is readable in the history. */
    @Column(name = "provider", nullable = false, length = 40)
    private String provider;

    /** The provider's order reference. Unique: one order is opened once. */
    @Column(name = "provider_order_id", nullable = false, length = 100)
    private String providerOrderId;

    /** The provider's payment reference, once there is one. Unique: one payment completes once. */
    @Column(name = "provider_payment_id", length = 100)
    private String providerPaymentId;

    /** What the fine was when the order was opened - the amount the provider was asked for. */
    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** When the attempt reached SUCCEEDED or FAILED. */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
