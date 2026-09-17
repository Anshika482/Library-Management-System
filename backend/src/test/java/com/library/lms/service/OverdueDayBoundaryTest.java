package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * Where the library's day ends: at midnight in Asia/Kolkata, on fixed clocks
 * one minute either side of it.
 *
 * <p>A loan due on the 15th is on time at 23:59 IST on the 15th and one day
 * overdue at 00:01 IST on the 16th. The last test holds the same instant on a
 * UTC clock, where it is still the 15th - which is why the JVM must run in the
 * business time zone.</p>
 */
class OverdueDayBoundaryTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final LocalDate DUE = LocalDate.of(2026, 3, 15);

    private static final LocalDateTime BEFORE_MIDNIGHT = DUE.atTime(23, 59);

    private static final LocalDateTime AFTER_MIDNIGHT = DUE.plusDays(1).atTime(0, 1);

    private static OverduePolicy policyAt(LocalDateTime istTime, ZoneId clockZone) {
        Clock clock = Clock.fixed(istTime.atZone(IST).toInstant(), clockZone);
        return new OverduePolicy(new BigDecimal("0.35"), clock);
    }

    @Test
    void aLoanDueTodayIsStillOnTimeAt2359Ist() {
        OverduePolicy policy = policyAt(BEFORE_MIDNIGHT, IST);

        assertThat(policy.today()).isEqualTo(DUE);
        assertThat(policy.isOverdue(DUE, policy.today())).isFalse();
        assertThat(policy.fineFor(DUE, policy.today())).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void itIsOneDayOverdueAt0001IstTheNextDay() {
        OverduePolicy policy = policyAt(AFTER_MIDNIGHT, IST);

        assertThat(policy.today()).isEqualTo(DUE.plusDays(1));
        assertThat(policy.isOverdue(DUE, policy.today())).isTrue();
        assertThat(policy.daysOverdue(DUE, policy.today())).isEqualTo(1);
        assertThat(policy.fineFor(DUE, policy.today())).isEqualTo(new BigDecimal("0.35"));
    }

    @Test
    void aReturnAt2359IsOnTimeAndAt0001IsLate() {
        LocalDate returnedBeforeMidnight = policyAt(BEFORE_MIDNIGHT, IST).today();
        LocalDate returnedAfterMidnight = policyAt(AFTER_MIDNIGHT, IST).today();

        assertThat(policyAt(BEFORE_MIDNIGHT, IST).fineFor(DUE, returnedBeforeMidnight))
                .isEqualTo(new BigDecimal("0.00"));
        assertThat(policyAt(AFTER_MIDNIGHT, IST).fineFor(DUE, returnedAfterMidnight))
                .isEqualTo(new BigDecimal("0.35"));
    }

    @Test
    void onAUtcClockTheSameInstantIsStillTheDueDate() {
        // 00:01 IST on the 16th is 18:31 UTC on the 15th. A JVM left in UTC
        // would keep this loan on time for another five and a half hours.
        OverduePolicy utc = policyAt(AFTER_MIDNIGHT, ZoneOffset.UTC);

        assertThat(utc.today()).isEqualTo(DUE);
        assertThat(utc.isOverdue(DUE, utc.today())).isFalse();
    }
}
