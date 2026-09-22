package com.library.lms.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.library.lms.entity.AuditAction;
import com.library.lms.entity.AuditEvent;
import com.library.lms.entity.AuditOutcome;
import com.library.lms.entity.AuditTargetType;

/**
 * Data access for {@link AuditEvent}: append, and read one library's events.
 *
 * <p><b>Narrower than every other repository on purpose.</b> It extends the
 * bare {@link Repository} rather than {@code JpaRepository}, so it has no
 * {@code findAll}, no update and no delete - only what is declared here. An
 * event can be added and never changed, and every read names the library it
 * reads, so no caller can list another library's events by forgetting a
 * filter.</p>
 */
@org.springframework.stereotype.Repository
public interface AuditEventRepository extends Repository<AuditEvent, Long> {

    /** Appends an event. There is no way to change or remove one afterwards. */
    AuditEvent save(AuditEvent event);

    /**
     * One page of a library's events, in the order the page asks for.
     *
     * @param libraryId the library whose events these are
     * @param pageable  page, size and sort
     * @return that library's events only
     */
    Page<AuditEvent> findByLibraryId(Long libraryId, Pageable pageable);

    /**
     * One page of a library's events, narrowed by whichever filters are given.
     *
     * <p>Every filter is optional: a null one drops out of the comparison and
     * matches everything. The library does not, and is not optional - it is the
     * first predicate, joined to the rest with {@code and}, so no combination of
     * filters can reach an event of another library.</p>
     *
     * <p>Named {@code findByLibraryId...} like the derived finders above
     * because the same rule holds either way: a read of this table starts from
     * one library.</p>
     *
     * @param libraryId   the library whose events these are
     * @param action      the action to match, or null for any
     * @param outcome     the outcome to match, or null for either
     * @param actorUserId the actor to match, or null for anyone
     * @param targetType  the kind of record to match, or null for any
     * @param targetId    the record id to match, or null for any
     * @param from        the earliest moment to include, or null for no floor
     * @param to          the latest moment to include, or null for no ceiling
     * @param pageable    page, size and sort
     * @return that library's matching events only
     */
    @Query("""
            select event from AuditEvent event
            where event.library.id = :libraryId
              and (:action is null or event.action = :action)
              and (:outcome is null or event.outcome = :outcome)
              and (:actorUserId is null or event.actorUserId = :actorUserId)
              and (:targetType is null or event.targetType = :targetType)
              and (:targetId is null or event.targetId = :targetId)
              and (:from is null or event.occurredAt >= :from)
              and (:to is null or event.occurredAt <= :to)
            """)
    Page<AuditEvent> findByLibraryIdMatching(@Param("libraryId") Long libraryId,
            @Param("action") AuditAction action,
            @Param("outcome") AuditOutcome outcome,
            @Param("actorUserId") Long actorUserId,
            @Param("targetType") AuditTargetType targetType,
            @Param("targetId") Long targetId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);

    /**
     * Everything recorded against one record of a library, oldest first.
     *
     * @param libraryId  the library the record belongs to
     * @param targetType the kind of record
     * @param targetId   its id
     * @return the library's events for that record only
     */
    List<AuditEvent> findByLibraryIdAndTargetTypeAndTargetIdOrderByIdAsc(Long libraryId, AuditTargetType targetType,
            Long targetId);
}
