package com.library.lms.dto;

import com.library.lms.entity.ResourceType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * What staff send to create or replace a digital resource.
 *
 * <p><b>No library field, deliberately.</b> The library comes from the
 * authenticated account, so a caller cannot attach a resource to another
 * library by naming one - and the book id is resolved within that same library,
 * so it cannot reach across either.</p>
 *
 * <p><b>The URL must be http or https.</b> Anything else - {@code javascript:},
 * {@code data:}, {@code file:} - is refused here and again in the service. A
 * link is eventually rendered by a browser, and those schemes are how a stored
 * link becomes script running in a reader's session or a path read off their
 * own machine.</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class DigitalResourceRequest {

    @NotNull(message = "A book id is required")
    private Long bookId;

    @NotBlank(message = "A title is required")
    @Size(max = 200, message = "A title must not exceed 200 characters")
    private String title;

    @Size(max = 1000, message = "A description must not exceed 1000 characters")
    private String description;

    @NotNull(message = "A resource type is required: PDF, EPUB, VIDEO or LINK")
    private ResourceType resourceType;

    @NotBlank(message = "A resource URL is required")
    @Size(max = 2048, message = "A resource URL must not exceed 2048 characters")
    @Pattern(regexp = "(?i)^https?://.+$", message = "A resource URL must start with http:// or https://")
    private String resourceUrl;

    /**
     * Whether members may see it. Absent means enabled: a resource is added to
     * be read, and staff can turn it off afterwards.
     */
    private Boolean enabled;
}
