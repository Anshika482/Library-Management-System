package com.library.lms.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.library.lms.entity.DigitalResource;

/**
 * Data access for {@link DigitalResource}.
 *
 * <p><b>Every finder names the library.</b> There is no {@code findById} in use
 * anywhere above this interface: a resource is always looked up by its id
 * <i>and</i> the caller's library, so one library's resource cannot be read,
 * edited or deleted from another by guessing an id. The enabled variants are
 * what members see; staff use the others.</p>
 */
@Repository
public interface DigitalResourceRepository extends JpaRepository<DigitalResource, Long> {

    /** One resource of a library, whatever its state - the staff lookup. */
    Optional<DigitalResource> findByIdAndLibraryId(Long id, Long libraryId);

    /** One enabled resource of a library - the member lookup. */
    Optional<DigitalResource> findByIdAndLibraryIdAndEnabledTrue(Long id, Long libraryId);

    /** A library's resources, in the order the page asks for. */
    Page<DigitalResource> findByLibraryId(Long libraryId, Pageable pageable);

    /** A library's enabled resources. */
    Page<DigitalResource> findByLibraryIdAndEnabledTrue(Long libraryId, Pageable pageable);

    /** One book's resources, within a library. */
    Page<DigitalResource> findByLibraryIdAndBookId(Long libraryId, Long bookId, Pageable pageable);

    /** One book's enabled resources, within a library. */
    Page<DigitalResource> findByLibraryIdAndBookIdAndEnabledTrue(Long libraryId, Long bookId, Pageable pageable);
}
