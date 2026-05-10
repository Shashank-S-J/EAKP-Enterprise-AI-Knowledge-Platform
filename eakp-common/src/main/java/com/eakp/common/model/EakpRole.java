package com.eakp.common.model;

/**
 * Centralised role constants for the EAKP platform.
 * Shared across all services to eliminate magic strings.
 */
public enum EakpRole {
    USER,
    ADMIN;

    /** Returns the Spring Security authority string, e.g. "ROLE_ADMIN". */
    public String authority() {
        return "ROLE_" + name();
    }

    /** Parse from string, case-insensitive. Returns USER as default. */
    public static EakpRole from(String value) {
        if (value == null) return USER;
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return USER;
        }
    }
}

