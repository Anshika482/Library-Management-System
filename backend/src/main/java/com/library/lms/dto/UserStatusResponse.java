package com.library.lms.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * What an administrator is told after changing an account's status.
 *
 * <p>The account's identity and its two switches, so the caller can see what
 * the change left behind without asking again. Nothing else from the user row
 * travels here - no password hash, no email, no role - because none of it is
 * needed to confirm a status change.</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class UserStatusResponse {

    private Long id;

    private String username;

    private boolean enabled;

    private boolean accountNonLocked;
}
