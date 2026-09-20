package com.example.compat.engine;

/**
 * A named, immutable rule set. Policies are versioned code (see PolicyRegistry);
 * a run permanently records its version, so re-evaluating saved inputs always
 * uses the same rules.
 */
public interface CompatibilityPolicy {
    String version();

    Severity severity(String code, Side side);
}
