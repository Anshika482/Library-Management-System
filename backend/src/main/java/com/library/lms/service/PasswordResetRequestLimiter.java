package com.library.lms.service;

/**
 * Decides whether a password reset may be asked for an email address right now.
 *
 * <p><b>The boundary a shared limiter would sit behind.</b> The implementation
 * the application runs with, {@link InMemoryPasswordResetRequestLimiter}, keeps
 * its counters in memory - per instance, and forgotten on restart - so several
 * instances multiply the limit. A deployment that needs one limit across every
 * instance replaces that class with one backed by a shared store; nothing that
 * calls this interface changes.</p>
 *
 * <p>Whatever the implementation, it is asked before any account is looked up,
 * so addresses with and without accounts are limited alike, and a refusal is
 * answered exactly as an accepted request is.</p>
 */
public interface PasswordResetRequestLimiter {

    /**
     * Counts a request for this address and says whether it may go ahead.
     *
     * @param email the address as submitted; implementations compare it
     *              trimmed and case-insensitively
     * @return true if the request is within the limit
     */
    boolean tryAcquire(String email);
}
