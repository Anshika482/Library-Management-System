package com.library.lms.dto;

import com.library.lms.entity.Role;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * An account as it is described back to an administrator.
 *
 * <p>Enough to confirm what was just created - who it is, what they may do, and
 * that the account is usable. <b>No password and no hash</b>: the hash is
 * credential material, and a response is the last place it should appear. There
 * is no field for it here at all, so no future edit can accidentally populate
 * one.</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {

    private Long id;

    private String username;

    private String email;

    private Role role;

    private boolean enabled;

    private boolean accountNonLocked;
}
