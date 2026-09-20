package com.example.compat.engine;

/** Evidence kind constants. Evidence is shown independently and excluded from pass-rate math. */
public final class StructuralEvidence {
    private StructuralEvidence() {
    }

    public static final String UNRESOLVABLE_REF = "UNRESOLVABLE_REF";
    public static final String CYCLE = "CYCLE";
    public static final String INCOMPLETE_SAMPLE = "INCOMPLETE_SAMPLE";
    public static final String UNMATCHED_SAMPLE = "UNMATCHED_SAMPLE";
}
