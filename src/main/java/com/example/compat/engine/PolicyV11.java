package com.example.compat.engine;

/**
 * compat-1.1: same structure as 1.0 but downgrades low-impact drift to INFO and
 * treats tightened request patterns as breaking. Demonstrates that the policy
 * version is part of the run identity.
 */
public final class PolicyV11 extends PolicyV1 {

    @Override
    public String version() {
        return "compat-1.1";
    }

    @Override
    public Severity severity(String code, Side side) {
        Severity override = switch (code) {
            case ChangeCodes.DEFAULT_CHANGED, ChangeCodes.DEFAULT_REMOVED,
                 ChangeCodes.FORMAT_CHANGED, ChangeCodes.PARAM_DEPRECATED -> Severity.INFO;
            case ChangeCodes.PATTERN_CHANGED -> side == Side.REQUEST
                    ? Severity.BREAKING : Severity.NON_BREAKING;
            case ChangeCodes.PARAM_STYLE_CHANGED -> Severity.INFO;
            default -> null;
        };
        return override != null ? override : super.severity(code, side);
    }
}
