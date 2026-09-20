package com.gsb.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Validates one parsed sample payload against a materialized {@link SchemaNode}.
 *
 * Returns violations in deterministic (pointer, rule) order.
 * {@link #touchedTrouble(SchemaNode, Object)} tells callers whether validation
 * depended on an unresolved reference or a cyclic schema: such results are shown
 * as evidence and must never be counted in pass-rate statistics.
 */
public final class SchemaValidator {

    private SchemaValidator() {
    }

    public static List<Violation> validate(SchemaNode node, Object data) {
        TreeSet<Violation> out = new TreeSet<>();
        validateAt(node, data, "$", out);
        return new ArrayList<>(out);
    }

    public static boolean touchedTrouble(SchemaNode node, Object data) {
        return troubleAt(node, data);
    }

    private static boolean troubleAt(SchemaNode node, Object data) {
        if (node == null) {
            return false;
        }
        if (node.isTrouble()) {
            return true;
        }
        if (data == null) {
            return false;
        }
        return switch (node.kind()) {
            case SchemaNode.OBJECT -> data instanceof Map<?, ?> map && objectTrouble(node, map);
            case SchemaNode.ARRAY -> data instanceof List<?> list && list.stream().anyMatch(i -> troubleAt(node.items(), i));
            case SchemaNode.ONE_OF, SchemaNode.ANY_OF -> node.alternatives().stream().anyMatch(a -> troubleAt(a, data));
            case SchemaNode.ALL_OF -> node.properties().keySet().stream().anyMatch(k -> {
                SchemaNode child = node.properties().get(k);
                return data instanceof Map<?, ?> m && m.containsKey(k) && troubleAt(child, ((Map<?, ?>) m).get(k));
            });
            default -> false;
        };
    }

    private static boolean objectTrouble(SchemaNode node, Map<?, ?> map) {
        for (Map.Entry<String, SchemaNode> e : node.properties().entrySet()) {
            if (map.containsKey(e.getKey()) && troubleAt(e.getValue(), map.get(e.getKey()))) {
                return true;
            }
        }
        return false;
    }

    private static void validateAt(SchemaNode node, Object data, String pointer, TreeSet<Violation> out) {
        if (node == null) {
            return;
        }
        if (node.isTrouble()) {
            out.add(new Violation(pointer, node.kind().equals(SchemaNode.CYCLE) ? "CYCLE" : "UNRESOLVED_REF",
                    node.kind().equals(SchemaNode.CYCLE)
                            ? "schema cycle at " + nullSafe(node.ref())
                            : "unresolved reference " + nullSafe(node.ref())));
            return;
        }
        if (data == null) {
            if (!Boolean.TRUE.equals(node.nullable()) && !SchemaNode.EMPTY.equals(node.kind())) {
                out.add(new Violation(pointer, "NULL_FORBIDDEN", "value is null but schema is not nullable"));
            }
            return;
        }
        switch (node.kind()) {
            case SchemaNode.EMPTY -> {
                // unconstrained
            }
            case SchemaNode.OBJECT -> validateObject(node, data, pointer, out);
            case SchemaNode.ARRAY -> validateArray(node, data, pointer, out);
            case SchemaNode.STRING -> {
                if (!(data instanceof String)) {
                    out.add(typeViolation(pointer, "string", data));
                    return;
                }
                String value = (String) data;
                if (node.minLength() != null && value.length() < node.minLength()) {
                    out.add(new Violation(pointer, "MIN_LENGTH", "length " + value.length() + " < " + node.minLength()));
                }
                if (node.maxLength() != null && value.length() > node.maxLength()) {
                    out.add(new Violation(pointer, "MAX_LENGTH", "length " + value.length() + " > " + node.maxLength()));
                }
                checkEnum(node, data, pointer, out);
            }
            case SchemaNode.INTEGER, SchemaNode.NUMBER -> validateNumber(node, data, pointer, out);
            case SchemaNode.BOOLEAN -> {
                if (!(data instanceof Boolean)) {
                    out.add(typeViolation(pointer, "boolean", data));
                }
            }
            case SchemaNode.NULL -> {
                if (data != null) {
                    out.add(new Violation(pointer, "TYPE", "expected null"));
                }
            }
            case SchemaNode.ONE_OF -> validateOneOf(node, data, pointer, out);
            case SchemaNode.ANY_OF -> validateAnyOf(node, data, pointer, out);
            case SchemaNode.ALL_OF -> validateAllOf(node, data, pointer, out);
            default -> {
            }
        }
    }

    private static void validateObject(SchemaNode node, Object data, String pointer, TreeSet<Violation> out) {
        if (!(data instanceof Map<?, ?> map)) {
            out.add(typeViolation(pointer, "object", data));
            return;
        }
        for (String requiredName : node.required()) {
            if (!map.containsKey(requiredName)) {
                out.add(new Violation(pointer + "." + requiredName, "REQUIRED",
                        "missing required property '" + requiredName + "'"));
            }
        }
        for (Map.Entry<String, SchemaNode> entry : node.properties().entrySet()) {
            if (map.containsKey(entry.getKey())) {
                validateAt(entry.getValue(), map.get(entry.getKey()),
                        pointer + "." + entry.getKey(), out);
            }
        }
    }

    private static void validateArray(SchemaNode node, Object data, String pointer, TreeSet<Violation> out) {
        if (!(data instanceof List<?> list)) {
            out.add(typeViolation(pointer, "array", data));
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            validateAt(node.items(), list.get(i), pointer + "[" + i + "]", out);
        }
    }

    private static void validateNumber(SchemaNode node, Object data, String pointer, TreeSet<Violation> out) {
        double number;
        if (data instanceof Number num) {
            number = num.doubleValue();
        } else {
            out.add(typeViolation(pointer, node.kind(), data));
            return;
        }
        if (SchemaNode.INTEGER.equals(node.kind()) && number != Math.rint(number)) {
            out.add(new Violation(pointer, "TYPE", "expected integer"));
        }
        if (node.minimum() != null) {
            boolean bad = Boolean.TRUE.equals(node.exclusiveMinimum())
                    ? number <= node.minimum() : number < node.minimum();
            if (bad) {
                out.add(new Violation(pointer, "MINIMUM",
                        number + (Boolean.TRUE.equals(node.exclusiveMinimum()) ? " <= " : " < ") + node.minimum()));
            }
        }
        if (node.maximum() != null) {
            boolean bad = Boolean.TRUE.equals(node.exclusiveMaximum())
                    ? number >= node.maximum() : number > node.maximum();
            if (bad) {
                out.add(new Violation(pointer, "MAXIMUM",
                        number + (Boolean.TRUE.equals(node.exclusiveMaximum()) ? " >= " : " > ") + node.maximum()));
            }
        }
        checkEnum(node, data, pointer, out);
    }

    private static void checkEnum(SchemaNode node, Object data, String pointer, TreeSet<Violation> out) {
        if (!node.enumValues().isEmpty() && !node.enumValues().contains(data)) {
            out.add(new Violation(pointer, "ENUM", "value " + data + " not in allowed enum"));
        }
    }

    private static void validateOneOf(SchemaNode node, Object data, String pointer, TreeSet<Violation> out) {
        int matches = 0;
        List<Violation> lastViolations = List.of();
        for (SchemaNode alt : node.alternatives()) {
            TreeSet<Violation> trial = new TreeSet<>();
            validateAt(alt, data, pointer, trial);
            if (trial.isEmpty()) {
                matches++;
            }
            lastViolations = new ArrayList<>(trial);
        }
        if (matches != 1) {
            out.add(new Violation(pointer, "ONE_OF",
                    matches == 0 ? "no oneOf branch matches" : "more than one oneOf branch matches"));
            if (matches == 0) {
                out.addAll(lastViolations);
            }
        }
    }

    private static void validateAnyOf(SchemaNode node, Object data, String pointer, TreeSet<Violation> out) {
        boolean any = false;
        for (SchemaNode alt : node.alternatives()) {
            TreeSet<Violation> trial = new TreeSet<>();
            validateAt(alt, data, pointer, trial);
            if (trial.isEmpty()) {
                any = true;
                break;
            }
        }
        if (!any) {
            out.add(new Violation(pointer, "ANY_OF", "no anyOf branch matches"));
        }
    }

    private static void validateAllOf(SchemaNode node, Object data, String pointer, TreeSet<Violation> out) {
        for (SchemaNode alt : node.alternatives()) {
            validateAt(alt, data, pointer, out);
        }
        if (node.alternatives().isEmpty()) {
            validateObject(node, data, pointer, out);
        }
    }

    private static Violation typeViolation(String pointer, String expected, Object data) {
        return new Violation(pointer, "TYPE", "expected " + expected + " but got "
                + (data == null ? "null" : data.getClass().getSimpleName()));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
