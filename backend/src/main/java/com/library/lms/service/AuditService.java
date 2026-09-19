package com.library.lms.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.entity.AuditAction;
import com.library.lms.entity.AuditEvent;
import com.library.lms.entity.AuditOutcome;
import com.library.lms.repository.AuditEventRepository;
import com.library.lms.repository.LibraryRepository;

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
 * in, and is refused without one.</p>
 */
@Service
public class AuditService {

    private final AuditEventRepository auditEventRepository;

    private final LibraryRepository libraryRepository;

    private final Clock clock;

    @Autowired
    public AuditService(AuditEventRepository auditEventRepository, LibraryRepository libraryRepository) {
        this(auditEventRepository, libraryRepository, Clock.systemDefaultZone());
    }

    AuditService(AuditEventRepository auditEventRepository, LibraryRepository libraryRepository, Clock clock) {
        this.auditEventRepository = auditEventRepository;
        this.libraryRepository = libraryRepository;
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
}
