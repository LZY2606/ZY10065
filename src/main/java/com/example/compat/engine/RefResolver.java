package com.example.compat.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves local JSON-Pointer references (#/...) inside one OpenAPI document.
 * External/URL/non-local refs are never fetched: they are recorded as
 * UNRESOLVABLE_REF evidence and shown independently.
 *
 * Cycle detection is stack-based per expansion (a diamond dependency is fine;
 * a reference that re-enters a node already on its own expansion stack is a cycle).
 */
public final class RefResolver {

    private final JsonNode root;
    private final Set<String> unresolvableRefs = new TreeSet<>();
    private final Set<String> cycleRefs = new TreeSet<>();

    public RefResolver(JsonNode root) {
        this.root = root;
    }

    public JsonNode root() {
        return root;
    }

    public Set<String> unresolvableRefs() {
        return unresolvableRefs;
    }

    public Set<String> cycleRefs() {
        return cycleRefs;
    }

    public boolean isLocalRef(JsonNode node) {
        return node != null && node.has("$ref") && node.get("$ref").asText().startsWith("#/");
    }

    /** Resolves a local pointer; returns null and records evidence when it cannot resolve. */
    public JsonNode resolve(String ref) {
        if (ref == null || !ref.startsWith("#/")) {
            unresolvableRefs.add(ref == null ? "(null)" : ref);
            return null;
        }
        JsonNode current = root;
        String[] parts = ref.substring(2).split("/");
        for (String rawPart : parts) {
            if (rawPart.isEmpty()) {
                continue;
            }
            String part = rawPart.replace("~1", "/").replace("~0", "~");
            if (current == null || !current.has(part)) {
                unresolvableRefs.add(ref);
                return null;
            }
            current = current.get(part);
        }
        return current;
    }

    /** Follows a chain of local refs from the given node, with cycle protection. */
    public JsonNode follow(JsonNode node, Set<String> stack) {
        JsonNode current = node;
        while (current != null && current.has("$ref")) {
            String ref = current.get("$ref").asText();
            if (!ref.startsWith("#/")) {
                unresolvableRefs.add(ref);
                ESchema marker = new ESchema();
                marker.getMarkers().put("UNRESOLVABLE_REF", ref);
                return null;
            }
            if (!stack.add(ref)) {
                cycleRefs.add(ref);
                return null;
            }
            current = resolve(ref);
        }
        return current;
    }

    void recordCycle(String ref) {
        cycleRefs.add(ref);
    }

    void recordUnresolvable(String ref) {
        unresolvableRefs.add(ref);
    }
}
