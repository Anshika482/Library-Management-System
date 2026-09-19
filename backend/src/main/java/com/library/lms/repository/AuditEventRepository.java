package com.library.lms.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import com.library.lms.entity.AuditEvent;
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
