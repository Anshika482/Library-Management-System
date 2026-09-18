package com.library.lms.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's scheduler.
 *
 * <p>Without this annotation a {@code @Scheduled} method is simply never
 * called - no warning, no error, and nothing in the log to say so. It is
 * enabled here, in a class of its own, rather than on the application class,
 * so that what is scheduled and why is one grep away.</p>
 *
 * <p>What runs on it today:
 * {@link com.library.lms.service.RefreshTokenService#purgeExpiredSessions()},
 * which removes refresh-token rows whose session ended longer ago than the
 * configured retention.</p>
 *
 * <p><b>Every instance schedules its own work.</b> There is no distributed
 * lock: the only scheduled task deletes rows that are already past their
 * retention, so two instances doing it at once simply means one of them finds
 * nothing left to delete.</p>
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
