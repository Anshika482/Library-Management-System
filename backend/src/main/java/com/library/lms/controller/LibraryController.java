package com.library.lms.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.library.lms.dto.CreateLibraryRequest;
import com.library.lms.dto.LibraryResponse;
import com.library.lms.service.LibraryService;

import jakarta.validation.Valid;

/**
 * Registration of new libraries.
 *
 * <p>One endpoint. There is no listing, no lookup and no rename: every library
 * is a separate tenant, and a route that described other tenants would be a
 * route that could leak one.</p>
 *
 * <p>Access is settled before this class is reached. The filter chain requires
 * the ADMIN authority for every method under {@code /api/libraries}, so a member
 * or a librarian is refused by the chain.</p>
 */
@RestController
@RequestMapping("/api/libraries")
public class LibraryController {

    private final LibraryService libraryService;

    public LibraryController(LibraryService libraryService) {
        this.libraryService = libraryService;
    }

    /**
     * Creates a library.
     *
     * <p>{@code @Valid} rejects a missing, blank or over-long name through the
     * existing validation handler before this method runs. The body carries a
     * name and nothing else, so neither the new library's id nor anything about
     * its creator can be chosen by the client.</p>
     *
     * <p>The new library starts with no accounts, and creating it does not make
     * the caller a member of it: their own account and library are unchanged.</p>
     *
     * @param request        the library to create
     * @param authentication the administrator, supplied by the filter chain
     * @return 201 with the new library
     */
    @PostMapping
    public ResponseEntity<LibraryResponse> createLibrary(@Valid @RequestBody CreateLibraryRequest request,
            Authentication authentication) {
        LibraryResponse created = libraryService.createLibrary(request, authentication.getName());

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
