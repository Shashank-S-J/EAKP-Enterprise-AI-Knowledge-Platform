package com.eakp.common.model;

/**
 * Centralised message role constants for chat messages.
 * Prevents magic strings like "USER", "ASSISTANT", "SYSTEM" across the codebase.
 */
public enum MessageRole {
    USER,
    ASSISTANT,
    SYSTEM;

    /** Parse from string, case-insensitive. Returns USER as default. */
    public static MessageRole from(String value) {
        if (value == null) return USER;
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return USER;
        }
    }
}

