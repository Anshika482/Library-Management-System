package com.library.lms.service;

import com.library.lms.entity.AuditTargetType;

/**
 * The record an audit event is about: its kind and its id.
 *
 * @param type the kind of record, or null when there is none
 * @param id   its id, or null when there is none
 */
public record AuditTarget(AuditTargetType type, Long id) {

    /** An account. */
    public static AuditTarget user(Long id) {
        return new AuditTarget(AuditTargetType.USER, id);
    }

    /** A library. */
    public static AuditTarget library(Long id) {
        return new AuditTarget(AuditTargetType.LIBRARY, id);
    }

    /** No record - the change was refused before one was identified. */
    public static AuditTarget none() {
        return new AuditTarget(null, null);
    }
}
