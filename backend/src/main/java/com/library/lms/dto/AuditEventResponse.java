package com.library.lms.dto;

import java.time.LocalDateTime;

import com.library.lms.entity.AuditAction;
import com.library.lms.entity.AuditOutcome;
import com.library.lms.entity.AuditTargetType;

/**
 * One audited change, as an administrator reads it back.
 *
 * <p>Exactly what the row holds - ids, names and a time. The library is not
 * repeated: every event a caller can read belongs to their own library.</p>
 *
 * @param actorUserId the account that made the change, or null when nobody was
 *                    signed in
 * @param targetType  what kind of record it was made to, or null when the
 *                    change was refused before one was identified
 */
public record AuditEventResponse(Long id, AuditAction action, AuditOutcome outcome, Long actorUserId,
        AuditTargetType targetType, Long targetId, LocalDateTime occurredAt) {
}
