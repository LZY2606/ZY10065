package com.example.compat.engine;

public enum Severity {
    BREAKING,
    NON_BREAKING,
    INFO,
    /** Structural evidence (unresolvable ref / cycle / incomplete sample): never in pass-rate math. */
    EVIDENCE
}
