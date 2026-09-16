package com.library.lms.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * What an administrator may change about an account.
 *
 * <p>Two fields, and deliberately only two. There is no role here, and no
 * library: this endpoint decides whether an account works, not what it may do
 * or whose it is. Adding either would turn a status switch into a way to grant
 * privileges.</p>
 *
 * <p><b>{@link Boolean}, not {@code boolean}.</b> Null means "leave this one
 * alone", so an administrator can lock an account without also having to
 * restate whether it is enabled. A primitive would default to false and
 * silently disable an account that the request never mentioned.</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class UserStatusRequest {

    /** True to let the account be used, false to retire it, null to leave it. */
    private Boolean enabled;

    /** True to clear an administrative lock, false to apply one, null to leave it. */
    private Boolean accountNonLocked;
}
