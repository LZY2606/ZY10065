package com.gsb;

import com.gsb.engine.SchemaNode;
import com.gsb.engine.SchemaValidator;
import com.gsb.engine.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaValidatorTest {

    private SchemaNode stringNode(Integer min, Integer max) {
        return new SchemaNode(SchemaNode.STRING, Map.of(), List.of(), null, null, null,
                List.of(), null, null, null, null, min, max, null, null, List.of(), null);
    }

    @Test
    void validatesRequiredNullableEnumAndNumericBounds() {
        SchemaNode node = new SchemaNode(SchemaNode.OBJECT,
                Map.of(
                        "name", stringNode(1, 50),
                        "kind", new SchemaNode(SchemaNode.STRING, Map.of(), List.of(), null,
                                null, null, List.of("dog", "cat"), null, null, null, null,
                                null, null, null, null, List.of(), null),
                        "rating", new SchemaNode(SchemaNode.NUMBER, Map.of(), List.of(), null,
                                null, null, List.of(), 0.0, 5.0, false, false, null, null,
                                null, null, List.of(), null),
                        "tag", new SchemaNode(SchemaNode.STRING, Map.of(), List.of(), null,
                                Boolean.TRUE, null, List.of(), null, null, null, null,
                                null, null, null, null, List.of(), null)),
                List.of("name", "kind"), null, null, null, List.of(), null, null,
                null, null, null, null, null, null, List.of(), null);

        java.util.Map<String, Object> good = new java.util.HashMap<>();
        good.put("name", "Rex");
        good.put("kind", "dog");
        good.put("rating", 5);
        good.put("tag", null);
        assertTrue(SchemaValidator.validate(node, good).isEmpty());

        List<Violation> bad = SchemaValidator.validate(node, Map.of(
                "name", "", "kind", "bird", "rating", 5.1, "tag", "x"));
        assertTrue(bad.stream().anyMatch(v -> v.rule().equals("REQUIRED") == false
                && v.pointer().contains("name") && v.rule().equals("MIN_LENGTH")));
        assertTrue(bad.stream().anyMatch(v -> v.rule().equals("ENUM")));
        assertTrue(bad.stream().anyMatch(v -> v.rule().equals("MAXIMUM")));

        List<Violation> missing = SchemaValidator.validate(node, Map.of("name", "x"));
        assertTrue(missing.stream().anyMatch(v -> v.rule().equals("REQUIRED")));
    }

    @Test
    void nonNullableNullIsViolation() {
        SchemaNode node = stringNode(null, null);
        assertTrue(SchemaValidator.validate(node, null).stream()
                .anyMatch(v -> v.rule().equals("NULL_FORBIDDEN")));
    }

    @Test
    void unresolvedRefIsReportedAsViolationAndTrouble() {
        SchemaNode node = new SchemaNode(SchemaNode.REF_UNRESOLVED, Map.of(), List.of(),
                null, null, null, List.of(), null, null, null, null, null, null, null,
                List.of(), "#/components/schemas/Missing");
        List<Violation> violations = SchemaValidator.validate(node, Map.of("a", 1));
        assertEquals(1, violations.size());
        assertEquals("UNRESOLVED_REF", violations.get(0).rule());
        assertTrue(SchemaValidator.touchedTrouble(node, Map.of("a", 1)));
    }

    @Test
    void troubleInUnusedPropertyDoesNotTaintWholePayload() {
        SchemaNode trouble = new SchemaNode(SchemaNode.REF_UNRESOLVED, Map.of(), List.of(),
                null, null, null, List.of(), null, null, null, null, null, null, null,
                List.of(), "#/x");
        SchemaNode root = new SchemaNode(SchemaNode.OBJECT, Map.of("broken", trouble),
                List.of(), null, null, null, List.of(), null, null, null, null, null,
                null, null, List.of(), null);
        assertFalse(SchemaValidator.touchedTrouble(root, Map.of("other", "present")));
        assertTrue(SchemaValidator.touchedTrouble(root, Map.of("broken", "data")));
    }

    @Test
    void violationsAreDeterministicallyOrdered() {
        SchemaNode node = new SchemaNode(SchemaNode.OBJECT,
                Map.of(
                        "a", stringNode(5, null),
                        "b", stringNode(5, null)),
                List.of("a", "b"), null, null, null, List.of(), null, null, null, null,
                null, null, null, null, List.of(), null);
        List<Violation> first = SchemaValidator.validate(node, Map.of("a", "x", "b", "y"));
        List<Violation> second = SchemaValidator.validate(node, Map.of("b", "y", "a", "x"));
        assertEquals(first, second);
        assertTrue(first.size() >= 2);
    }
}
