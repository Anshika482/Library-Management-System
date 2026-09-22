package com.library.lms.controller;

import java.time.LocalDateTime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.library.lms.dto.AuditEventResponse;
import com.library.lms.dto.PagedResponse;
import com.library.lms.entity.AuditAction;
import com.library.lms.entity.AuditOutcome;
import com.library.lms.entity.AuditTargetType;
import com.library.lms.service.AuditEventFilter;
import com.library.lms.service.AuditService;

/**
 * Reading a library's audit log.
 *
 * <p>Administrators only, and their own library only - the filter chain
 * requires the ADMIN authority for every verb on this path, and
 * {@link AuditService} takes the library from the caller's account rather than
 * the request. Reading is not itself audited.</p>
 */
@RestController
@RequestMapping("/api/audit-events")
public class AuditEventController {

    private final AuditService auditService;

    public AuditEventController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * GET /api/audit-events - one page of the caller's library's audit log.
     *
     * <p>Newest first unless asked otherwise. Pages follow the rest of the API:
     * {@code page} from 0 and {@code size} 1 to 50. Only {@code occurredAt} and
     * {@code id} can be sorted on - there is nothing else worth ordering by, and
     * a caller cannot name a column.</p>
     *
     * <p>Every filter given must match: {@code action}, {@code outcome},
     * {@code actorUserId}, {@code targetType} with {@code targetId}, and a
     * {@code from}/{@code to} range over when the change happened. Times are
     * ISO-8601 local date-times in the library's time zone, such as
     * {@code 2026-09-20T09:30:00}.</p>
     */
    @GetMapping
    public ResponseEntity<PagedResponse<AuditEventResponse>> findEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "occurredAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) AuditOutcome outcome,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) AuditTargetType targetType,
            @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            Authentication authentication) {
        AuditEventFilter filter = new AuditEventFilter(action, outcome, actorUserId, targetType, targetId, from, to);

        return ResponseEntity.ok(auditService.findEvents(page, size, sortBy, direction, filter,
                authentication.getName()));
    }
}
