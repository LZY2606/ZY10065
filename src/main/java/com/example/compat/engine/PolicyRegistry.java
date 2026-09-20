package com.example.compat.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PolicyRegistry {

    private static final Map<String, CompatibilityPolicy> POLICIES = new LinkedHashMap<>();

    static {
        register(new PolicyV1());
        register(new PolicyV11());
    }

    private PolicyRegistry() {
    }

    private static void register(CompatibilityPolicy policy) {
        POLICIES.put(policy.version(), policy);
    }

    public static CompatibilityPolicy require(String version) {
        CompatibilityPolicy policy = POLICIES.get(version);
        if (policy == null) {
            throw new IllegalArgumentException(
                    "unknown compatibility policy version: " + version
                            + "; known versions: " + POLICIES.keySet());
        }
        return policy;
    }

    public static List<String> versions() {
        return List.copyOf(POLICIES.keySet());
    }
}
