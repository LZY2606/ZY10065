package com.gsb.engine;

import java.util.List;
import java.util.Map;

/**
 * A fully materialized, resolved schema node used by both validation and diffing.
 *
 * Special terminal kinds:
 *  - REF_UNRESOLVED: $ref target could not be resolved; {@link #ref()} holds it
 *  - CYCLE: a reference back into an active resolution chain; {@link #ref()} holds it
 *  - EMPTY: {} / unconstrained schema (accepts anything except maybe null rules)
 */
public record SchemaNode(
        String kind,
        Map<String, SchemaNode> properties,
        List<String> required,
        SchemaNode items,
        Boolean nullable,
        Object defaultValue,
        List<Object> enumValues,
        Double minimum,
        Double maximum,
        Boolean exclusiveMinimum,
        Boolean exclusiveMaximum,
        Integer minLength,
        Integer maxLength,
        String discriminatorProperty,
        Map<String, String> discriminatorMapping,
        List<SchemaNode> alternatives,
        String ref
) {
    /** Compact constructor used by tests: no discriminator mapping. */
    public SchemaNode(
            String kind,
            Map<String, SchemaNode> properties,
            List<String> required,
            SchemaNode items,
            Boolean nullable,
            Object defaultValue,
            List<Object> enumValues,
            Double minimum,
            Double maximum,
            Boolean exclusiveMinimum,
            Boolean exclusiveMaximum,
            Integer minLength,
            Integer maxLength,
            String discriminatorProperty,
            List<SchemaNode> alternatives,
            String ref
    ) {
        this(kind, properties, required, items, nullable, defaultValue, enumValues,
                minimum, maximum, exclusiveMinimum, exclusiveMaximum, minLength, maxLength,
                discriminatorProperty, Map.of(), alternatives, ref);
    }

    public static final String OBJECT = "object";
    public static final String ARRAY = "array";
    public static final String STRING = "string";
    public static final String NUMBER = "number";
    public static final String INTEGER = "integer";
    public static final String BOOLEAN = "boolean";
    public static final String NULL = "null";
    public static final String ONE_OF = "oneOf";
    public static final String ANY_OF = "anyOf";
    public static final String ALL_OF = "allOf";
    public static final String REF_UNRESOLVED = "ref-unresolved";
    public static final String CYCLE = "cycle";
    public static final String EMPTY = "empty";

    public boolean isTrouble() {
        return REF_UNRESOLVED.equals(kind) || CYCLE.equals(kind);
    }
}
