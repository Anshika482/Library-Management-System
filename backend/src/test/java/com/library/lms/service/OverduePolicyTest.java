package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.library.lms.entity.TransactionStatus;

/**
 * The overdue rule and the fine arithmetic, on a clock that does not move.
 *
 * <p>No context and no database: every answer here is a function of a due
 * date, a day and a rate. The rate is 0.35 on purpose - an amount that
 * {@code double} arithmetic gets wrong, so a fine calculated that way would
 * fail here rather than show a customer 1.0499999999999998.</p>
 *
 * <p>How the rule reaches the API is in {@code OverdueFineIntegrationTest}.</p>
 */
class OverduePolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    private static final Clock FIXED = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private final OverduePolicy policy = new OverduePolicy(new BigDecimal("0.35"), FIXED);

    // ---------- when a loan is overdue ----------

    @Test
    void todayComesFromTheClock() {
        assertThat(policy.today()).isEqualTo(TODAY);
    }

    @Test
    void aLoanIsNotOverdueOnOrBeforeItsDueDate() {
        for (LocalDate dueDate : new LocalDate[] {TODAY, TODAY.plusDays(1), TODAY.plusDays(30)}) {
            assertThat(policy.isOverdue(dueDate, TODAY)).as("due %s", dueDate).isFalse();
            assertThat(policy.daysOverdue(dueDate, TODAY)).isZero();
            assertThat(policy.fineFor(dueDate, TODAY)).isEqualTo(new BigDecimal("0.00"));
        }
    }

    @Test
    void aLoanIsOverdueFromTheDayAfterItsDueDate() {
        LocalDate yesterday = TODAY.minusDays(1);

        assertThat(policy.isOverdue(yesterday, TODAY)).isTrue();
        assertThat(policy.daysOverdue(yesterday, TODAY)).isEqualTo(1);
        assertThat(policy.fineFor(yesterday, TODAY)).isEqualTo(new BigDecimal("0.35"));
    }

    @Test
    void aMissingDueDateIsNeverLate() {
        assertThat(policy.isOverdue(null, TODAY)).isFalse();
        assertThat(policy.fineFor(null, TODAY)).isEqualTo(new BigDecimal("0.00"));
    }

    // ---------- what it costs ----------

    @Test
    void theFineIsTheDaysPastDueTimesTheRateExactly() {
        BigDecimal threeDays = policy.fineFor(TODAY.minusDays(3), TODAY);

        assertThat(threeDays).isEqualTo(new BigDecimal("1.05"));
        assertThat(threeDays.doubleValue()).as("and it is stored without drift").isEqualTo(1.05);
        assertThat(3 * 0.35).as("which double arithmetic would not have managed").isNotEqualTo(1.05);

        assertThat(policy.fineFor(TODAY.minusDays(30), TODAY)).isEqualTo(new BigDecimal("10.50"));
    }

    @Test
    void daysAreCountedAcrossMonthAndYearEnds() {
        assertThat(policy.daysOverdue(LocalDate.of(2025, 12, 30), LocalDate.of(2026, 1, 2))).isEqualTo(3);
        assertThat(policy.daysOverdue(LocalDate.of(2024, 2, 28), LocalDate.of(2024, 3, 1))).as("leap day").isEqualTo(2);
    }

    @Test
    void aReturnIsJudgedByItsReturnDateNotByToday() {
        LocalDate dueDate = TODAY.minusDays(20);

        assertThat(policy.fineFor(dueDate, dueDate.minusDays(2))).as("back early").isEqualTo(new BigDecimal("0.00"));
        assertThat(policy.fineFor(dueDate, dueDate)).as("back on the day").isEqualTo(new BigDecimal("0.00"));
        assertThat(policy.fineFor(dueDate, dueDate.plusDays(4)))
                .as("back four days late")
                .isEqualTo(new BigDecimal("1.40"));
    }

    @Test
    void askingAgainNeverChangesTheAnswer() {
        LocalDate dueDate = TODAY.minusDays(7);
        BigDecimal first = policy.fineFor(dueDate, TODAY);

        for (int i = 0; i < 5; i++) {
            assertThat(policy.fineFor(dueDate, TODAY)).isEqualTo(first);
        }
        assertThat(first).isEqualTo(new BigDecimal("2.45"));
    }

    @Test
    void onlyIssuedAndOverdueLoansAreOpen() {
        assertThat(policy.isOpen(TransactionStatus.ISSUED)).isTrue();
        assertThat(policy.isOpen(TransactionStatus.OVERDUE)).isTrue();
        assertThat(policy.isOpen(TransactionStatus.RETURNED)).isFalse();
        assertThat(policy.isOpen(null)).isFalse();
    }

    // ---------- the configured rate ----------

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "1.5", "2.50", "100.00", " 0.35 ", "1.500"})
    void aSensibleRateIsAcceptedWithTwoDecimalPlaces(String configured) {
        BigDecimal rate = new OverduePolicy(configured).dailyRate();

        assertThat(rate.scale()).isEqualTo(2);
        assertThat(rate).isEqualByComparingTo(configured.trim());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "-1", "0.001", "1.005", "abc", "1,50", "NaN", "", "   "})
    void aRateThatCannotBeChargedStopsStartup(String configured) {
        assertThatThrownBy(() -> new OverduePolicy(configured))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(OverduePolicy.DAILY_RATE_PROPERTY);
    }

    @Test
    void aZeroRateStillReportsLoansOverdue() {
        OverduePolicy free = new OverduePolicy(BigDecimal.ZERO, FIXED);

        assertThat(free.isOverdue(TODAY.minusDays(3), TODAY)).isTrue();
        assertThat(free.fineFor(TODAY.minusDays(3), TODAY)).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void theRateComesFromTheEnvironmentWithADefault() throws Exception {
        Properties development = new Properties();
        try (InputStream file = OverduePolicyTest.class.getResourceAsStream("/application.properties")) {
            assertThat(file).isNotNull();
            development.load(file);
        }

        assertThat(development.getProperty(OverduePolicy.DAILY_RATE_PROPERTY)).isEqualTo("${FINE_DAILY_RATE:1.00}");
    }
}
