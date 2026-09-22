package com.library.lms.dto;

import java.time.LocalDateTime;

import com.library.lms.entity.ResourceType;

/**
 * A digital resource as the API returns it.
 *
 * <p>The library is not repeated: every resource a caller can read belongs to
 * their own library. The book is named as well as identified, so a list is
 * readable without a second call.</p>
 *
 * @param enabled whether members can see it; a member's own list only ever
 *                contains enabled ones
 */
public record DigitalResourceResponse(Long id, Long bookId, String bookTitle, String title, String description,
        ResourceType resourceType, String resourceUrl, boolean enabled, LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
