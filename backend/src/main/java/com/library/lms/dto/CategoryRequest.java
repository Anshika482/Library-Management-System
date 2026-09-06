package com.library.lms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * What a client sends when creating a category.
 *
 * <p>One field, because a category is one field. There is no {@code id} here
 * for the same reason BookRequest has none: the database assigns it.</p>
 *
 * <p>{@code @NotBlank} is doing more work than it appears to. Its contract is
 * "not null, and at least one non-whitespace character", so it rejects
 * {@code null}, {@code ""} and {@code "   "} in one annotation - which is
 * exactly the rule "the name must not be empty once trimmed". The service then
 * trims before saving, so " Fiction " and "Fiction" store identically.</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class CategoryRequest {

    @NotBlank(message = "Category name is required")
    @Size(max = 100, message = "Category name must not exceed 100 characters")
    private String name;
}
