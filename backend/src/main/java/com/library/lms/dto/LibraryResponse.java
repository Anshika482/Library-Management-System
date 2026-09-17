package com.library.lms.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * A library as it is described back to the administrator who created it.
 *
 * <p>The id the database assigned, the name as stored - trimmed, so a client
 * can see exactly what was kept - when it was registered, and its first
 * administrator. That account is described the way the user endpoint describes
 * any account, so with no password and no hash. Nothing here concerns the
 * creator, whose own account is untouched.</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class LibraryResponse {

    private Long id;

    private String name;

    private LocalDateTime createdAt;

    private UserResponse admin;
}
