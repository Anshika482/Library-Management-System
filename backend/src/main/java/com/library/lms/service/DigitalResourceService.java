package com.library.lms.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.dto.DigitalResourceRequest;
import com.library.lms.dto.DigitalResourceResponse;
import com.library.lms.dto.PagedResponse;
import com.library.lms.entity.Book;
import com.library.lms.entity.DigitalResource;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.exception.BookNotFoundException;
import com.library.lms.exception.DigitalResourceAccessDeniedException;
import com.library.lms.exception.DigitalResourceNotFoundException;
import com.library.lms.exception.InvalidPaginationException;
import com.library.lms.exception.InvalidSortException;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.DigitalResourceRepository;
import com.library.lms.repository.UserRepository;

/**
 * Digital resources: what a library offers to read or watch online.
 *
 * <p><b>Scoped to the caller's library, always.</b> The library comes from the
 * caller's own account, never from the request, and every lookup names it. A
 * resource of another library is not found - the same 404 an id belonging to
 * nobody gets - so no id can be probed from outside.</p>
 *
 * <p><b>Staff write, members read.</b> Administrators and librarians of a
 * library create, change, enable, disable and delete its resources. Members may
 * only see the enabled ones. The filter chain enforces the first half; this
 * class enforces it again, because a rule that exists in one place is one edit
 * away from not existing.</p>
 *
 * <p><b>Disabled means invisible, not deleted.</b> A member's list and lookup
 * both filter on enabled, and a disabled resource answers a member with the
 * same 404 as one that was never there - so turning a resource off does not
 * quietly advertise that it exists.</p>
 *
 * <p><b>A reference, never a file.</b> What is stored is a URL, and it must be
 * http or https: a {@code javascript:} or {@code data:} link stored here would
 * become script running in a reader's browser the first time a page rendered
 * it. The request's own validation says the same thing; this is the second
 * check, for callers that reach the service another way.</p>
 */
@Service
public class DigitalResourceService {

    private static final Logger log = LoggerFactory.getLogger(DigitalResourceService.class);

    /** The most resources one page may hold - the same ceiling as every other list in the API. */
    private static final int MAX_PAGE_SIZE = 50;

    /**
     * The sort names this list accepts, each mapped to the entity property it
     * sorts by. The value handed to {@code Sort.by} is always the right-hand
     * side, so a caller can never name a property this map does not list.
     */
    private static final Map<String, String> SORTABLE_FIELDS = Map.of(
            "id", "id",
            "title", "title",
            "resourceType", "resourceType",
            "createdAt", "createdAt",
            "updatedAt", "updatedAt");

    private final DigitalResourceRepository resourceRepository;

    private final BookRepository bookRepository;

    private final UserRepository userRepository;

    public DigitalResourceService(DigitalResourceRepository resourceRepository,
                                  BookRepository bookRepository,
                                  UserRepository userRepository) {
        this.resourceRepository = resourceRepository;
        this.bookRepository = bookRepository;
        this.userRepository = userRepository;
    }

    // ---------- what staff may do ----------

    /**
     * Adds a resource to a book of the caller's library.
     *
     * @throws DigitalResourceAccessDeniedException if the caller is a member
     * @throws BookNotFoundException                if the caller's library has
     *                                              no book with that id
     */
    @Transactional
    public DigitalResourceResponse create(DigitalResourceRequest request, String authenticatedUsername) {
        User staff = requireStaff(authenticatedUsername);
        Long libraryId = staff.getLibrary().getId();
        Book book = bookOfLibrary(request.getBookId(), libraryId);
        String url = validUrl(request.getResourceUrl());

        LocalDateTime now = LocalDateTime.now();

        DigitalResource resource = new DigitalResource();
        resource.setLibrary(staff.getLibrary());
        resource.setBook(book);
        resource.setTitle(request.getTitle().trim());
        resource.setDescription(trimmedOrNull(request.getDescription()));
        resource.setResourceType(request.getResourceType());
        resource.setResourceUrl(url);
        resource.setEnabled(request.getEnabled() == null || request.getEnabled());
        resource.setCreatedAt(now);
        resource.setUpdatedAt(now);

        DigitalResource saved = resourceRepository.save(resource);

        // Ids and a type. Not the URL, which may carry a signed token later.
        log.info("Digital resource created by username='{}': id={} book id={} type={} library id={}",
                staff.getUsername(), saved.getId(), book.getId(), saved.getResourceType(), libraryId);

        return toResponse(saved);
    }

    /**
     * Replaces a resource of the caller's library, including which book it is
     * attached to.
     *
     * @throws DigitalResourceAccessDeniedException if the caller is a member
     * @throws DigitalResourceNotFoundException     if the caller's library has
     *                                              no resource with that id
     */
    @Transactional
    public DigitalResourceResponse update(Long id, DigitalResourceRequest request, String authenticatedUsername) {
        User staff = requireStaff(authenticatedUsername);
        Long libraryId = staff.getLibrary().getId();
        DigitalResource resource = resourceOfLibrary(id, libraryId);
        Book book = bookOfLibrary(request.getBookId(), libraryId);
        String url = validUrl(request.getResourceUrl());

        resource.setBook(book);
        resource.setTitle(request.getTitle().trim());
        resource.setDescription(trimmedOrNull(request.getDescription()));
        resource.setResourceType(request.getResourceType());
        resource.setResourceUrl(url);
        if (request.getEnabled() != null) {
            resource.setEnabled(request.getEnabled());
        }
        resource.setUpdatedAt(LocalDateTime.now());

        DigitalResource saved = resourceRepository.save(resource);

        log.info("Digital resource updated by username='{}': id={} book id={} library id={}",
                staff.getUsername(), saved.getId(), book.getId(), libraryId);

        return toResponse(saved);
    }

    /**
     * Turns a resource on or off for members.
     *
     * @throws DigitalResourceAccessDeniedException if the caller is a member
     * @throws DigitalResourceNotFoundException     if the caller's library has
     *                                              no resource with that id
     */
    @Transactional
    public DigitalResourceResponse setEnabled(Long id, boolean enabled, String authenticatedUsername) {
        User staff = requireStaff(authenticatedUsername);
        Long libraryId = staff.getLibrary().getId();
        DigitalResource resource = resourceOfLibrary(id, libraryId);

        resource.setEnabled(enabled);
        resource.setUpdatedAt(LocalDateTime.now());

        DigitalResource saved = resourceRepository.save(resource);

        log.info("Digital resource {} by username='{}': id={} library id={}",
                enabled ? "enabled" : "disabled", staff.getUsername(), saved.getId(), libraryId);

        return toResponse(saved);
    }

    /**
     * Removes a resource from the caller's library. The file it pointed at is
     * not touched - this application does not host it.
     *
     * @throws DigitalResourceAccessDeniedException if the caller is a member
     * @throws DigitalResourceNotFoundException     if the caller's library has
     *                                              no resource with that id
     */
    @Transactional
    public void delete(Long id, String authenticatedUsername) {
        User staff = requireStaff(authenticatedUsername);
        Long libraryId = staff.getLibrary().getId();
        DigitalResource resource = resourceOfLibrary(id, libraryId);

        resourceRepository.delete(resource);

        log.info("Digital resource deleted by username='{}': id={} library id={}",
                staff.getUsername(), id, libraryId);
    }

    // ---------- what everyone may do ----------

    /**
     * One page of the caller's library's resources.
     *
     * <p>Staff see every resource; a member sees only the enabled ones,
     * whatever they ask for. {@code bookId} narrows the list to one book, still
     * within the caller's library.</p>
     *
     * @throws InvalidPaginationException if page or size is out of range
     * @throws InvalidSortException       if the field or direction is unsupported
     */
    @Transactional(readOnly = true)
    public PagedResponse<DigitalResourceResponse> list(int page, int size, String sortBy, String direction,
            Long bookId, String authenticatedUsername) {
        User caller = authenticatedUser(authenticatedUsername);
        Long libraryId = caller.getLibrary().getId();

        validatePagination(page, size);
        Pageable pageable = PageRequest.of(page, size, resolveSort(sortBy, direction));

        // A member's page is the enabled one. Not a filter they pass - one they
        // cannot turn off.
        boolean enabledOnly = !isStaff(caller);

        Page<DigitalResource> resources;
        if (bookId != null) {
            resources = enabledOnly
                    ? resourceRepository.findByLibraryIdAndBookIdAndEnabledTrue(libraryId, bookId, pageable)
                    : resourceRepository.findByLibraryIdAndBookId(libraryId, bookId, pageable);
        } else {
            resources = enabledOnly
                    ? resourceRepository.findByLibraryIdAndEnabledTrue(libraryId, pageable)
                    : resourceRepository.findByLibraryId(libraryId, pageable);
        }

        return new PagedResponse<>(
                resources.getContent().stream().map(DigitalResourceService::toResponse).toList(),
                resources.getNumber(),
                resources.getSize(),
                resources.getTotalElements(),
                resources.getTotalPages());
    }

    /**
     * One resource of the caller's library.
     *
     * @throws DigitalResourceNotFoundException if the library has no resource
     *                                          with that id, or it is disabled
     *                                          and the caller is a member
     */
    @Transactional(readOnly = true)
    public DigitalResourceResponse getById(Long id, String authenticatedUsername) {
        User caller = authenticatedUser(authenticatedUsername);
        Long libraryId = caller.getLibrary().getId();

        DigitalResource resource = isStaff(caller)
                ? resourceOfLibrary(id, libraryId)
                : resourceRepository.findByIdAndLibraryIdAndEnabledTrue(id, libraryId)
                        .orElseThrow(() -> new DigitalResourceNotFoundException(id));

        return toResponse(resource);
    }

    // ---------- the rules ----------

    /** The caller, if they are staff of their library. */
    private User requireStaff(String authenticatedUsername) {
        User caller = authenticatedUser(authenticatedUsername);

        if (!isStaff(caller)) {
            throw new DigitalResourceAccessDeniedException();
        }

        return caller;
    }

    private static boolean isStaff(User user) {
        return user.getRole() == Role.ROLE_ADMIN || user.getRole() == Role.ROLE_LIBRARIAN;
    }

    /** A resource of this library, or a 404 that says nothing about other libraries. */
    private DigitalResource resourceOfLibrary(Long id, Long libraryId) {
        return resourceRepository.findByIdAndLibraryId(id, libraryId)
                .orElseThrow(() -> new DigitalResourceNotFoundException(id));
    }

    /** A book of this library, or the same 404 a book that never existed gets. */
    private Book bookOfLibrary(Long bookId, Long libraryId) {
        return bookRepository.findByIdAndLibraryId(bookId, libraryId)
                .orElseThrow(() -> new BookNotFoundException(bookId));
    }

    /**
     * The URL, if it is one this application will store.
     *
     * <p>http and https only. The request's {@code @Pattern} says the same
     * thing at the HTTP boundary; this is the check for anything that reaches
     * the service another way, and the place the rule actually lives.</p>
     */
    static String validUrl(String resourceUrl) {
        String url = resourceUrl == null ? "" : resourceUrl.trim();

        URI parsed;
        try {
            parsed = new URI(url);
        } catch (URISyntaxException malformed) {
            throw new IllegalArgumentException("A resource URL must be a valid http:// or https:// address");
        }

        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || parsed.getHost() == null) {
            throw new IllegalArgumentException("A resource URL must be a valid http:// or https:// address");
        }

        return url;
    }

    private static String trimmedOrNull(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return description.trim();
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

        return "id".equals(property) ? sort : sort.and(Sort.by(Sort.Direction.ASC, "id"));
    }

    private User authenticatedUser(String authenticatedUsername) {
        return userRepository.findByUsername(authenticatedUsername)
                .orElseThrow(() -> new UserNotFoundException(authenticatedUsername));
    }

    /** A resource as it leaves the API. The library is not repeated - it is the caller's own. */
    private static DigitalResourceResponse toResponse(DigitalResource resource) {
        Book book = resource.getBook();

        return new DigitalResourceResponse(
                resource.getId(),
                book == null ? null : book.getId(),
                book == null ? null : book.getTitle(),
                resource.getTitle(),
                resource.getDescription(),
                resource.getResourceType(),
                resource.getResourceUrl(),
                resource.isEnabled(),
                resource.getCreatedAt(),
                resource.getUpdatedAt());
    }
}
