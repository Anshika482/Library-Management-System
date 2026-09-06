package com.library.lms.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * What the API sends back to a client describing a category.
 *
 * <p>Two fields and nothing else. The {@link com.library.lms.entity.Category}
 * entity happens to carry the same two today, which makes the DTO look
 * redundant - but that is exactly when it earns its keep. When Category later
 * grows columns the API should not publish, this class is what stops them
 * appearing in every response by accident.</p>
 *
 * <p>The {@code id} is the value a client sends back as {@code categoryId} when
 * creating or updating a book; the {@code name} is what a person reads.</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class CategoryResponse {

    private Long id;

    private String name;
}
