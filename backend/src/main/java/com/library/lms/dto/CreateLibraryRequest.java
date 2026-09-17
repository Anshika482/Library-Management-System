package com.library.lms.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * What an administrator supplies to create a library: its name and its first
 * administrator.
 *
 * <p><b>No id.</b> The database assigns one - a client-chosen id would turn
 * "create" into "overwrite the library that already has it".</p>
 *
 * <p><b>Nothing about the creator.</b> There is no user, no role and no "make
 * me a member of it". Creating a library does not change the creator's
 * account, so the request has no way to ask for that.</p>
 *
 * <p><b>The first administrator is required.</b> A library with no account in
 * it is one nobody could ever log in to. {@code @Valid} carries validation into
 * {@link FirstAdminRequest}, so a weak password or a malformed email is refused
 * before anything is written.</p>
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

    @NotNull(message = "First administrator is required")
    @Valid
    private FirstAdminRequest admin;
}
