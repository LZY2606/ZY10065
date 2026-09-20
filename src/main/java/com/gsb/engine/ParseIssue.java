package com.gsb.engine;

public record ParseIssue(String kind, String pointer, String detail) {
    public static final String UNRESOLVED_REF = "UNRESOLVED_REF";
    public static final String CYCLE = "CYCLE";
    public static final String BAD_SPEC = "BAD_SPEC";
}
