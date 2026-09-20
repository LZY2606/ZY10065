package com.gsb.engine;

public record Violation(String pointer, String rule, String detail) implements Comparable<Violation> {
    @Override
    public int compareTo(Violation o) {
        int byPointer = pointer.compareTo(o.pointer);
        if (byPointer != 0) {
            return byPointer;
        }
        int byRule = rule.compareTo(o.rule);
        return byRule != 0 ? byRule : detail.compareTo(o.detail);
    }
}
