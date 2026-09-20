package com.gsb.service;

import java.util.Comparator;
import java.util.Optional;

/**
 * Compatibility policy versions. The only difference between the two supported
 * policies is how RESPONSE-direction REQUIRED_ADDED is classified:
 * legacy 2024.09 treats it as a WARNING, strict 2025.1 treats it as BREAKING.
 */
public enum Policy {

    LEGACY("2024.09", "legacy", false),
    STRICT("2025.1", "strict", true);

    private final String version;
    private final String label;
    private final boolean responseRequiredAddedBreaking;

    Policy(String version, String label, boolean responseRequiredAddedBreaking) {
        this.version = version;
        this.label = label;
        this.responseRequiredAddedBreaking = responseRequiredAddedBreaking;
    }

    public String version() {
        return version;
    }

    public String label() {
        return label;
    }

    public boolean isResponseRequiredAddedBreaking() {
        return responseRequiredAddedBreaking;
    }

    public static Policy resolve(String version) {
        String v = version == null ? "" : version.trim();
        for (Policy p : values()) {
            if (p.version.equals(v)) {
                return p;
            }
        }
        throw new IllegalArgumentException(
                "unknown policyVersion '" + version + "', supported: 2024.09, 2025.1");
    }

    /**
     * Reclassifies an engine-produced severity according to this policy.
     * Inputs are deterministic; sorting callers is not needed.
     */
    public String classify(String code, String direction, String engineSeverity) {
        if ("REQUIRED_ADDED".equals(code)
                && "RESPONSE".equals(direction)
                && !responseRequiredAddedBreaking) {
            return "WARNING";
        }
        return engineSeverity;
    }

    public static String latestVersion() {
        return STRICT.version;
    }

    /** Deterministic ordering helper for exposure in JSON. */
    public static Optional<Policy> byVersionSorted(String version) {
        return java.util.Arrays.stream(values())
                .sorted(Comparator.comparing(Policy::version))
                .filter(p -> p.version.equals(version))
                .findFirst();
    }
}
