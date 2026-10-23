package com.shiftrepair.model;

/** Stable company-defined code; matching is exact and case-sensitive. */
public record Qualification(Type type, String code) {
    public enum Type { ROLE, SKILL, CERTIFICATION }

    public Qualification {
        java.util.Objects.requireNonNull(type, "type");
        code = ModelChecks.text(code, "code");
    }
}
