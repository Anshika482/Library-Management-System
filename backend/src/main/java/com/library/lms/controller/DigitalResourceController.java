package com.library.lms.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.library.lms.dto.DigitalResourceRequest;
import com.library.lms.dto.DigitalResourceResponse;
import com.library.lms.dto.DigitalResourceStatusRequest;
import com.library.lms.dto.PagedResponse;
import com.library.lms.service.DigitalResourceService;

import jakarta.validation.Valid;

/**
 * Digital resources: what a library offers to read or watch online.
 *
 * <p>Staff of a library manage its resources; every signed-in account of that
 * library can read the enabled ones. Nothing here is public. The library is
 * taken from the caller's account by {@link DigitalResourceService}, never from
 * the request, so no path or body can reach another library's resources.</p>
 */
@RestController
@RequestMapping("/api/digital-resources")
public class DigitalResourceController {

    private final DigitalResourceService digitalResourceService;

    public DigitalResourceController(DigitalResourceService digitalResourceService) {
        this.digitalResourceService = digitalResourceService;
    }

    /**
     * POST /api/digital-resources - adds a resource to a book of the caller's
     * library. Staff only; answers 201.
     */
    @PostMapping
    public ResponseEntity<DigitalResourceResponse> create(@Valid @RequestBody DigitalResourceRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(digitalResourceService.create(request, authentication.getName()));
    }

    /**
     * PUT /api/digital-resources/{id} - replaces one. Staff only.
     *
     * <p>Every field is sent, including the book it belongs to; omitting
     * {@code enabled} leaves the current state alone.</p>
     */
    @PutMapping("/{id}")
    public ResponseEntity<DigitalResourceResponse> update(@PathVariable Long id,
            @Valid @RequestBody DigitalResourceRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(digitalResourceService.update(id, request, authentication.getName()));
    }

    /**
     * PATCH /api/digital-resources/{id}/status - turns one on or off for
     * members. Staff only.
     *
     * <p>Separate from the full update because it is the operation staff
     * actually reach for when a licence lapses or a link rots, and it needs
     * none of the other fields.</p>
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<DigitalResourceResponse> setStatus(@PathVariable Long id,
            @Valid @RequestBody DigitalResourceStatusRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(
                digitalResourceService.setEnabled(id, request.getEnabled(), authentication.getName()));
    }

    /**
     * DELETE /api/digital-resources/{id} - removes one. Staff only; answers
     * 204. The file it pointed at is not touched: this application does not
     * host it.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Authentication authentication) {
        digitalResourceService.delete(id, authentication.getName());

        return ResponseEntity.noContent().build();
    }

    /**
     * GET /api/digital-resources - one page of the caller's library's
     * resources.
     *
     * <p>Staff see every resource; a member sees the enabled ones and cannot
     * ask for more. {@code bookId} narrows the page to one book. Pages follow
     * the rest of the API: {@code page} from 0, {@code size} 1 to 50, and
     * {@code sortBy} one of id, title, resourceType, createdAt or updatedAt.</p>
     */
    @GetMapping
    public ResponseEntity<PagedResponse<DigitalResourceResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) Long bookId,
            Authentication authentication) {
        return ResponseEntity.ok(
                digitalResourceService.list(page, size, sortBy, direction, bookId, authentication.getName()));
    }

    /**
     * GET /api/digital-resources/{id} - one resource of the caller's library.
     *
     * <p>A disabled one is a 404 to a member: the same answer an id that does
     * not exist gets, so turning a resource off does not advertise it.</p>
     */
    @GetMapping("/{id}")
    public ResponseEntity<DigitalResourceResponse> getById(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(digitalResourceService.getById(id, authentication.getName()));
    }
}
