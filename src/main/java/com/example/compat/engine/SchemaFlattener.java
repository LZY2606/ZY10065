package com.example.compat.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builds an {@link ESchema} effective schema: refs resolved, allOf merged,
 * oneOf/anyOf branches retained, discriminator retained, nullable merged
 * (OpenAPI 3.0 style "nullable: true"). Unresolvable refs and cycles become
 * markers (independent evidence), never exceptions and never pass-rate input.
 */
public final class SchemaFlattener {

    private final RefResolver resolver;

    public SchemaFlattener(RefResolver resolver) {
        this.resolver = resolver;
    }

    public ESchema flatten(JsonNode schema) {
        return flatten(schema, new TreeSet<>());
    }

    private ESchema flatten(JsonNode raw, Set<String> stack) {
        if (raw == null || raw.isMissingNode() || raw.isNull()) {
            return null;
        }
        JsonNode node = raw;
        if (node.has("$ref")) {
            String ref = node.get("$ref").asText();
            Set<String> nextStack = new TreeSet<>(stack);
            if (!ref.startsWith("#/")) {
                resolver.recordUnresolvable(ref);
                ESchema broken = new ESchema();
                broken.setRef(ref);
                broken.getMarkers().put("UNRESOLVABLE_REF", ref);
                applySiblingOverrides(broken, node);
                return broken;
            }
            if (!nextStack.add(ref)) {
                resolver.recordCycle(ref);
                ESchema cyclic = new ESchema();
                cyclic.setRef(ref);
                cyclic.getMarkers().put("CYCLE", ref);
                return cyclic;
            }
            JsonNode target = resolver.resolve(ref);
            if (target == null) {
                ESchema broken = new ESchema();
                broken.setRef(ref);
                broken.getMarkers().put("UNRESOLVABLE_REF", ref);
                applySiblingOverrides(broken, node);
                return broken;
            }
            ESchema resolved = flatten(target, nextStack);
            applySiblingOverrides(resolved, node);
            return resolved;
        }
        return build(node, stack);
    }

    private ESchema build(JsonNode node, Set<String> stack) {
        ESchema schema = new ESchema();
        if (node.has("type")) {
            schema.setType(node.get("type").asText());
        }
        text(node, "format", schema::setFormat);
        text(node, "description", schema::setDescription);
        if (node.path("nullable").asBoolean(false)) {
            schema.setNullable(true);
        }
        if (node.path("readOnly").asBoolean(false)) {
            schema.setReadOnly(true);
        }
        if (node.path("writeOnly").asBoolean(false)) {
            schema.setWriteOnly(true);
        }
        if (node.path("deprecated").asBoolean(false)) {
            schema.setDeprecated(true);
        }
        if (node.has("default") && !node.get("default").isContainerNode()) {
            schema.setDefaultValue(node.get("default").asText());
        }
        if (node.has("enum")) {
            List<String> values = new ArrayList<>();
            for (JsonNode value : node.get("enum")) {
                values.add(value.asText());
            }
            schema.setEnumValues(values);
        }
        number(node, "minimum", schema::setMinimum);
        number(node, "maximum", schema::setMaximum);
        bool(node, "exclusiveMinimum", schema::setExclusiveMinimum);
        bool(node, "exclusiveMaximum", schema::setExclusiveMaximum);
        integer(node, "minLength", schema::setMinLength);
        integer(node, "maxLength", schema::setMaxLength);
        text(node, "pattern", schema::setPattern);
        integer(node, "minItems", schema::setMinItems);
        integer(node, "maxItems", schema::setMaxItems);
        if (node.path("uniqueItems").asBoolean(false)) {
            schema.setUniqueItems(true);
        }
        if (node.has("properties") && node.get("properties").isObject()) {
            TreeMap<String, ESchema> properties = new TreeMap<>();
            node.get("properties").fields().forEachRemaining(entry ->
                    properties.put(entry.getKey(), flatten(entry.getValue(), stack)));
            schema.setProperties(properties);
        }
        if (node.has("required") && node.get("required").isArray()) {
            TreeMap<String, String> required = new TreeMap<>();
            for (JsonNode name : node.get("required")) {
                required.put(name.asText(), name.asText());
            }
            schema.setRequired(required);
        }
        if (node.has("items")) {
            schema.setItems(flatten(node.get("items"), stack));
        }
        if (node.has("additionalProperties")) {
            JsonNode additional = node.get("additionalProperties");
            if (additional.isBoolean()) {
                if (!additional.asBoolean()) {
                    schema.setAdditionalProperties(new ESchema());
                    schema.getAdditionalProperties().getMarkers().put("CLOSED", "false");
                }
            } else {
                schema.setAdditionalProperties(flatten(additional, stack));
            }
        }
        if (node.has("oneOf") && node.get("oneOf").isArray()) {
            schema.setOneOf(flattenBranches(node.get("oneOf"), stack));
        }
        if (node.has("anyOf") && node.get("anyOf").isArray()) {
            schema.setAnyOf(flattenBranches(node.get("anyOf"), stack));
        }
        if (node.has("discriminator")) {
            JsonNode discriminator = node.get("discriminator");
            if (discriminator.has("propertyName")) {
                schema.setDiscriminatorProperty(discriminator.get("propertyName").asText());
            }
            if (discriminator.has("mapping") && discriminator.get("mapping").isObject()) {
                TreeMap<String, String> mapping = new TreeMap<>();
                discriminator.get("mapping").fields().forEachRemaining(entry ->
                        mapping.put(entry.getKey(), entry.getValue().asText()));
                schema.setDiscriminatorMapping(mapping);
            }
        }
        if (node.has("allOf") && node.get("allOf").isArray()) {
            for (JsonNode part : node.get("allOf")) {
                ESchema merged = flatten(part, stack);
                if (merged != null) {
                    mergeAllOf(schema, merged);
                }
            }
        }
        return schema;
    }

    private List<ESchema> flattenBranches(JsonNode array, Set<String> stack) {
        List<ESchema> branches = new ArrayList<>();
        for (JsonNode branch : array) {
            ESchema flattened = flatten(branch, stack);
            if (flattened != null) {
                branches.add(flattened);
            }
        }
        return branches;
    }

    private void applySiblingOverrides(ESchema schema, JsonNode node) {
        if (schema == null) {
            return;
        }
        if (node.path("nullable").asBoolean(false)) {
            schema.setNullable(true);
        }
        if (node.path("deprecated").asBoolean(false)) {
            schema.setDeprecated(true);
        }
        text(node, "description", schema::setDescription);
    }

    private void mergeAllOf(ESchema target, ESchema source) {
        if (source.hasBlockingMarker()) {
            target.getMarkers().putAll(source.getMarkers());
        }
        if (target.getType() == null && source.getType() != null) {
            target.setType(source.getType());
        }
        if (source.isNullable()) {
            target.setNullable(true);
        }
        for (var entry : source.getProperties().entrySet()) {
            target.getProperties().putIfAbsent(entry.getKey(), entry.getValue());
        }
        target.getRequired().putAll(source.getRequired());
        if (target.getItems() == null && source.getItems() != null) {
            target.setItems(source.getItems());
        }
        if (!source.getOneOf().isEmpty()) {
            target.setOneOf(source.getOneOf());
        }
        if (!source.getAnyOf().isEmpty()) {
            target.setAnyOf(source.getAnyOf());
        }
        if (source.getDiscriminatorProperty() != null && target.getDiscriminatorProperty() == null) {
            target.setDiscriminatorProperty(source.getDiscriminatorProperty());
            target.setDiscriminatorMapping(source.getDiscriminatorMapping());
        }
        if (source.getMinimum() != null && target.getMinimum() == null) {
            target.setMinimum(source.getMinimum());
        }
        if (source.getMaximum() != null && target.getMaximum() == null) {
            target.setMaximum(source.getMaximum());
        }
    }

    private interface Setter {
        void set(String value);
    }

    private void text(JsonNode node, String field, Setter setter) {
        if (node.has(field) && node.get(field).isTextual()) {
            setter.set(node.get(field).asText());
        }
    }

    private void number(JsonNode node, String field, java.util.function.Consumer<Double> setter) {
        if (node.has(field) && node.get(field).isNumber()) {
            setter.accept(node.get(field).asDouble());
        }
    }

    private void integer(JsonNode node, String field, java.util.function.Consumer<Integer> setter) {
        if (node.has(field) && node.get(field).isIntegralNumber()) {
            setter.accept(node.get(field).asInt());
        }
    }

    private void bool(JsonNode node, String field, java.util.function.Consumer<Boolean> setter) {
        if (node.has(field) && node.get(field).isBoolean()) {
            setter.accept(node.get(field).asBoolean());
        }
    }
}
