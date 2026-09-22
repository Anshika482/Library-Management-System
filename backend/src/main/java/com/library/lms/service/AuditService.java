package com.library.lms.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.dto.AuditEventResponse;
import com.library.lms.dto.PagedResponse;
import com.library.lms.entity.AuditAction;
import com.library.lms.entity.AuditEvent;
import com.library.lms.entity.AuditOutcome;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.exception.AuditAccessDeniedException;
import com.library.lms.exception.InvalidPaginationException;
import com.library.lms.exception.InvalidSortException;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.AuditEventRepository;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.UserRepository;

/**
 * The one way an audit event is recorded.
 *
 * <p><b>Two methods, because success and failure need opposite transactions.</b></p>
 * <ul>
 *   <li>{@link #recordSuccess} joins the business operation's transaction,
 *       and refuses to run without one. The event commits with the change it
 *       describes, or is rolled back with it - so the log never claims a change
 *       that did not happen, and a change never happens without its entry.</li>
 *   <li>{@link #recordFailure} runs in a transaction of its own. A refusal is
 *       reported by throwing, which rolls the business transaction back; in
 *       that transaction the record of the refusal would go too. Its own
 *       transaction commits whatever happens next.</li>
 * </ul>
 *
 * <p><b>Only ids and names.</b> Callers pass an action, a library id, an actor
 * id and a target; there is no parameter that could carry a password, a token
 * or an address, and nothing is logged here.</p>
 *
 * <p><b>Library-scoped.</b> Every event needs the library the change happened
 * in, and is refused without one - and {@link #findEvents} reads back within
 * one library for the same reason.</p>
 */
@Service
public class AuditService {

    private final AuditEventRepository auditEventRepository;

    private final LibraryRepository libraryRepository;

    private final UserRepository userRepository;

    private final Clock clock;

    @Autowired
    public AuditService(AuditEventRepository auditEventRepository, LibraryRepository libraryRepository,
            UserRepository userRepository) {
        this(auditEventRepository, libraryRepository, userRepository, Clock.systemDefaultZone());
    }

    AuditService(AuditEventRepository auditEventRepository, LibraryRepository libraryRepository,
            UserRepository userRepository, Clock clock) {
        this.auditEventRepository = auditEventRepository;
        this.libraryRepository = libraryRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /**
     * Records a change that went through, as part of the transaction making it.
     *
     * @param action      what was done
     * @param libraryId   the library it was done in
     * @param actorUserId who did it, or null when nobody was signed in
     * @param target      what it was done to
     * @throws org.springframework.transaction.IllegalTransactionStateException
     *         if there is no transaction to join
     * @throws IllegalArgumentException if the action, library or target is
     *                                  missing
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSuccess(AuditAction action, Long libraryId, Long actorUserId, AuditTarget target) {
        record(action, libraryId, actorUserId, target, AuditOutcome.SUCCESS);
    }

    /**
     * Records a change that was refused, in a transaction of its own so the
     * refusal's rollback does not erase it.
     *
     * @param action      what was attempted
     * @param libraryId   the library it was attempted in
     * @param actorUserId who attempted it, or null when nobody was signed in
     * @param target      what it was attempted on, or {@link AuditTarget#none()}
     * @throws IllegalArgumentException if the action, library or target is
     *                                  missing
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(AuditAction action, Long libraryId, Long actorUserId, AuditTarget target) {
        record(action, libraryId, actorUserId, target, AuditOutcome.FAILURE);
    }

    private void record(AuditAction action, Long libraryId, Long actorUserId, AuditTarget target,
            AuditOutcome outcome) {
        if (action == null) {
            throw new IllegalArgumentException("An audit event needs an action");
        }
        if (libraryId == null) {
            throw new IllegalArgumentException("An audit event belongs to a library");
        }
        if (target == null) {
            throw new IllegalArgumentException("An audit event needs a target, or AuditTarget.none()");
        }

        auditEventRepository.save(new AuditEvent(libraryRepository.getReferenceById(libraryId), actorUserId, action,
                target.type(), target.id(), outcome, LocalDateTime.now(clock)));
    }

    // ---------- reading the log ----------

    /** The most events one page may hold - the same ceiling as every other list in the API. */
    private static final int MAX_PAGE_SIZE = 50;

    /**
     * The sort names the log accepts, each mapped to the entity property it
     * sorts by. The value handed to {@code Sort.by} is always the right-hand
     * side, so a caller can never name a property this map does not list.
     *
     * <p>Two, and only two: when a change happened, and the order it was
     * recorded in. Nothing else about an event is worth ordering by, and
     * sorting by actor or target would let a caller shape the log around one
     * person.</p>
     */
    private static final Map<String, String> SORTABLE_FIELDS = Map.of(
            "occurredAt", "occurredAt",
            "id", "id");

    /**
     * One page of the caller's library's audit log.
     *
     * <p><b>Administrators only.</b> The filter chain already restricts
     * {@code /api/audit-events} to them; this is the second lock, so a loosened
     * rule does not open the log on its own. Librarians and members are refused
     * with {@link AuditAccessDeniedException} - the log records what staff did
     * to accounts, and reading it is an administrator's job.</p>
     *
     * <p><b>Scoped to the caller's library, always.</b> The library comes from
     * the caller's own account, never from the request, and it is the first
     * predicate of the query - every filter narrows within it and none can
     * reach past it.</p>
     *
     * <p><b>Newest first by default</b>, because the recent end of a log is the
     * end anyone looks at. Equal timestamps are broken by id so paging stays
     * stable: events recorded in the same microsecond would otherwise be free
     * to swap between pages.</p>
     *
     * <p><b>Reading is not itself audited.</b> Recording every read would bury
     * the changes the log exists to show, and an administrator listing their own
     * library's events changes nothing.</p>
     *
     * @param filter                what to match, or null for the whole log
     * @param authenticatedUsername the caller, from the security context
     * @throws AuditAccessDeniedException if the caller is not an administrator
     * @throws InvalidPaginationException if page or size is out of range
     * @throws InvalidSortException       if the field or direction is
     *                                    unsupported
     */
    @Transactional(readOnly = true)
    public PagedResponse<AuditEventResponse> findEvents(int page, int size, String sortBy, String direction,
            AuditEventFilter filter, String authenticatedUsername) {
        User caller = userRepository.findByUsername(authenticatedUsername)
                .orElseThrow(() -> new UserNotFoundException(authenticatedUsername));

        if (caller.getRole() != Role.ROLE_ADMIN) {
            throw new AuditAccessDeniedException();
        }

        validatePagination(page, size);
        Pageable pageable = PageRequest.of(page, size, resolveSort(sortBy, direction));
        AuditEventFilter criteria = filter == null ? AuditEventFilter.none() : filter;

        Page<AuditEvent> events = auditEventRepository.findByLibraryIdMatching(
                caller.getLibrary().getId(),
                criteria.action(),
                criteria.outcome(),
                criteria.actorUserId(),
                criteria.targetType(),
                criteria.targetId(),
                criteria.from(),
                criteria.to(),
                pageable);

        return new PagedResponse<>(
                events.getContent().stream().map(AuditService::toResponse).toList(),
                events.getNumber(),
                events.getSize(),
                events.getTotalElements(),
                events.getTotalPages());
    }

    /** The same page rules as the rest of the API, so one ceiling holds everywhere. */
    private static void validatePagination(int page, int size) {
        if (page < 0) {
            throw new InvalidPaginationException("Page must be 0 or greater, but was " + page);
        }
        if (size < 1) {
            throw new InvalidPaginationException("Size must be at least 1, but was " + size);
        }
        if (size > MAX_PAGE_SIZE) {
            throw new InvalidPaginationException("Size must not exceed " + MAX_PAGE_SIZE + ", but was " + size);
        }
    }

    /** Resolves a caller's sort name to a property this class lists, or refuses it. */
    private static Sort resolveSort(String sortBy, String direction) {
        String property = SORTABLE_FIELDS.get(sortBy);
        if (property == null) {
            throw new InvalidSortException("Unsupported sort field. Allowed fields are: "
                    + String.join(", ", new TreeSet<>(SORTABLE_FIELDS.keySet())));
        }

        Sort.Direction sortDirection;
        if ("asc".equalsIgnoreCase(direction)) {
            sortDirection = Sort.Direction.ASC;
        } else if ("desc".equalsIgnoreCase(direction)) {
            sortDirection = Sort.Direction.DESC;
        } else {
            throw new InvalidSortException("Unsupported sort direction. Allowed directions are: asc, desc");
        }

        Sort sort = Sort.by(sortDirection, property);

        return "id".equals(property) ? sort : sort.and(Sort.by(sortDirection, "id"));
    }

    /** An event as it leaves the API: ids, names and a time, and nothing else. */
    private static AuditEventResponse toResponse(AuditEvent event) {
        return new AuditEventResponse(
                event.getId(),
                event.getAction(),
                event.getOutcome(),
                event.getActorUserId(),
                event.getTargetType(),
                event.getTargetId(),
                event.getOccurredAt());
    }
}
