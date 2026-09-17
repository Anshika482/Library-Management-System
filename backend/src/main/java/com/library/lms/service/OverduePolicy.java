package com.library.lms.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.library.lms.entity.TransactionStatus;

/**
 * When a loan is overdue, and what it costs.
 *
 * <p><b>One rule, one rate.</b> A loan still out after its due date is overdue
 * from the next day, and owes the daily rate for every day since its due date.
 * A book back on its due date owes nothing. The rate is
 * {@code library.fines.daily-rate}, supplied by FINE_DAILY_RATE, and it is the
 * same for every library and every book.</p>
 *
 * <p><b>Worked out from dates, never added up.</b> Nothing adds a day's fine to
 * a running total. Every answer is calculated afresh from the due date and the
 * date asked about, so asking twice - or a thousand times - gives the same
 * amount, and there is no total that could be counted twice.</p>
 *
 * <p><b>Money in {@link BigDecimal}.</b> A rate times a number of days is exact
 * here; in {@code double}, three days at 0.35 is 1.0499999999999998. Every
 * amount has exactly two decimal places, and a two-place amount survives
 * conversion to the stored {@code Double} unchanged.</p>
 *
 * <p><b>Today comes from here too.</b> Issue dates, return dates and the date
 * an open loan is judged against all read this one clock, so they cannot
 * disagree about what day it is. In production that is the system clock in the
 * server's time zone - the same date {@code LocalDate.now()} gives.</p>
 */
@Component
public class OverduePolicy {

    static final String DAILY_RATE_PROPERTY = "library.fines.daily-rate";

    /**
     * The states in which a book is still out: the only loans that can be
     * overdue, and the only ones that can be returned.
     *
     * <p>OVERDUE is here although the application never stores it, so that a
     * row which does carry it is treated as the open loan it describes. Naming
     * the open states, rather than taking "anything but RETURNED", means a
     * status added later is not open until somebody decides it is.</p>
     */
    public static final Set<TransactionStatus> OPEN_STATUSES =
            Set.of(TransactionStatus.ISSUED, TransactionStatus.OVERDUE);

    private final BigDecimal dailyRate;

    private final Clock clock;

    /**
     * The policy the application runs with: the configured rate, on the system
     * clock.
     *
     * @param dailyRate the configured fine per day, as written in configuration
     * @throws IllegalStateException if the rate is missing, not a number,
     *                               negative, or finer than two decimal places
     */
    @Autowired
    public OverduePolicy(@Value("${" + DAILY_RATE_PROPERTY + "}") String dailyRate) {
        this(parse(dailyRate), Clock.systemDefaultZone());
    }

    /**
     * For tests, which need a date that does not move.
     *
     * @param dailyRate the fine per day
     * @param clock     where today's date comes from
     * @throws IllegalStateException if the rate is negative or finer than two
     *                               decimal places
     */
    OverduePolicy(BigDecimal dailyRate, Clock clock) {
        if (dailyRate.signum() < 0) {
            throw new IllegalStateException(DAILY_RATE_PROPERTY + " must not be negative. Set FINE_DAILY_RATE"
                    + " to the fine per overdue day, or to 0 to charge nothing.");
        }
        if (dailyRate.stripTrailingZeros().scale() > 2) {
            throw new IllegalStateException(DAILY_RATE_PROPERTY + " must have at most two decimal places:"
                    + " a fine is charged in whole hundredths.");
        }

        this.dailyRate = dailyRate.setScale(2);
        this.clock = clock;
    }

    private static BigDecimal parse(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(DAILY_RATE_PROPERTY + " is not set. Set FINE_DAILY_RATE to the"
                    + " fine per overdue day, such as 1.00.");
        }

        try {
            return new BigDecimal(configured.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(DAILY_RATE_PROPERTY + " is not a number. Set FINE_DAILY_RATE to"
                    + " the fine per overdue day, such as 1.00.");
        }
    }

    /** Today, as far as loans are concerned. */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** The fine for one overdue day, with two decimal places. */
    public BigDecimal dailyRate() {
        return dailyRate;
    }

    /**
     * Whether a loan in this state is still out.
     *
     * @param status the stored state, possibly null
     * @return true for ISSUED and OVERDUE
     */
    public boolean isOpen(TransactionStatus status) {
        return status != null && OPEN_STATUSES.contains(status);
    }

    /**
     * How many days past its due date a loan is on a given day.
     *
     * <p>Zero on the due date itself and before it. A loan with no due date -
     * which the column does not allow - is never late.</p>
     *
     * @param dueDate the loan's due date
     * @param asOf    the day being asked about: today for an open loan, the
     *                return date for one that came back
     * @return the whole days after the due date, never negative
     */
    public long daysOverdue(LocalDate dueDate, LocalDate asOf) {
        if (dueDate == null || asOf == null || !asOf.isAfter(dueDate)) {
            return 0;
        }

        return ChronoUnit.DAYS.between(dueDate, asOf);
    }

    /** Whether a loan with this due date is overdue on the given day. */
    public boolean isOverdue(LocalDate dueDate, LocalDate asOf) {
        return daysOverdue(dueDate, asOf) > 0;
    }

    /**
     * The fine for a loan with this due date, counted to the given day.
     *
     * @param dueDate the loan's due date
     * @param asOf    today for an open loan, the return date for one that came
     *                back
     * @return the days overdue times the daily rate, with two decimal places -
     *         zero when the loan is not late
     */
    public BigDecimal fineFor(LocalDate dueDate, LocalDate asOf) {
        return dailyRate.multiply(BigDecimal.valueOf(daysOverdue(dueDate, asOf)));
    }
}
