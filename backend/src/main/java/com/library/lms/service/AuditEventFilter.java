package com.library.lms.service;

import java.time.LocalDateTime;

import com.library.lms.entity.AuditAction;
import com.library.lms.entity.AuditOutcome;
import com.library.lms.entity.AuditTargetType;

/**
 * What an administrator is looking for in their library's audit log.
 *
 * <p>Every field is optional, and those given must all match. None of them can
 * widen the search past the caller's own library: the library is not part of
 * this filter at all - the service takes it from the caller's account.</p>
 *
 * @param from events at or after this moment
 * @param to   events at or before this moment
 */
public record AuditEventFilter(AuditAction action, AuditOutcome outcome, Long actorUserId,
        AuditTargetType targetType, Long targetId, LocalDateTime from, LocalDateTime to) {

    /** No filter: the library's whole log. */
    public static AuditEventFilter none() {
        return new AuditEventFilter(null, null, null, null, null, null, null);
    }
}
