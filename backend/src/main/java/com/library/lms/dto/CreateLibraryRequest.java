package com.library.lms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * What an administrator supplies to create a library.
 *
 * <p><b>A name, and nothing else.</b> There is no id, because the database
 * assigns one - a client-chosen id would turn "create" into "overwrite the
 * library that already has it". And there is nothing about the person creating
 * it: no user, no role, no "make me its administrator". Creating a library
 * changes nobody's account, including the creator's, so the request has no way
 * to ask for that.</p>
 *
 * <p>{@code @NotBlank} rejects null, empty and whitespace-only names in one
 * rule, and the service trims what remains before checking and storing it, so
 * " Central " and "Central" are the same library. 100 characters is the
 * column's length.</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class CreateLibraryRequest {

    @NotBlank(message = "Library name is required")
    @Size(max = 100, message = "Library name must not exceed 100 characters")
    private String name;
}
