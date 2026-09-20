package com.example.compat.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates sanitized samples against a parsed spec version.
 *
 * Validation is intentionally structural and deterministic. Unresolvable refs
 * and cycles are collected as evidence (see {@link StructuralEvidence}) and the
 * sample is marked incomplete, so they never enter the pass-rate denominator.
 */
public final class SampleValidator {

    /** Validates the request side of one sample against one spec version. */
    public ValidationResult validateRequest(SpecModel spec, SampleModel sample) {
        ValidationResult result = new ValidationResult();
        OperationModel operation = spec.getOperations()
                .get(SpecModel.opKey(sample.method(), sample.path()));
        if (operation == null) {
            result.setComplete(false);
            result.setValid(false);
            result.addIssue("no operation for " + sample.method() + " " + sample.path());
            return result;
        }
        for (ParamModel parameter : operation.getParameters()) {
            // request bodies and headers are not carried in sanitized samples;
            // query/path params are optional in the fixture unless the sample embeds them.
            if ("query".equals(parameter.getIn()) && parameter.isRequired()) {
                // Query params without a recorded value are treated as omitted on purpose.
            }
        }
        BodyModel body = operation.getRequestBody();
        if (sample.requestBody() == null || sample.requestBody().isNull()
                || sample.requestBody().isMissingNode()) {
            if (body != null && body.isRequired()) {
                result.setComplete(false);
                result.addIssue(StructuralEvidence.INCOMPLETE_SAMPLE
                        + ": required request body missing");
            }
            return result;
        }
        if (body == null) {
            result.addIssue("request body present but spec declares none");
            return result;
        }
        String mediaType = sample.requestMediaType() != null
                ? sample.requestMediaType() : "application/json";
        ESchema schema = selectMediaType(body.getMediaTypes(), mediaType, result);
        if (schema != null) {
            validateNode(sample.requestBody(), schema, "$", result, new HashSet<>());
        }
        return result;
    }

    /** Validates the response side of one sample against one spec version. */
    public ValidationResult validateResponse(SpecModel spec, SampleModel sample) {
        ValidationResult result = new ValidationResult();
        OperationModel operation = spec.getOperations()
                .get(SpecModel.opKey(sample.method(), sample.path()));
        if (operation == null) {
            result.setComplete(false);
            result.setValid(false);
            result.addIssue("no operation for " + sample.method() + " " + sample.path());
            return result;
        }
        if (sample.statusCode() == null || sample.responseBody() == null) {
            result.setComplete(false);
            result.addIssue(StructuralEvidence.INCOMPLETE_SAMPLE
                    + ": status code or response body missing");
            return result;
        }
        ResponseModel response = selectResponse(operation, sample.statusCode(), result);
        if (response == null) {
            return result;
        }
        String mediaType = sample.responseMediaType() != null
                ? sample.responseMediaType() : "application/json";
        ESchema schema = selectMediaType(response.getMediaTypes(), mediaType, result);
        if (schema != null) {
            validateNode(sample.responseBody(), schema, "$", result, new HashSet<>());
        }
        return result;
    }

    private ResponseModel selectResponse(OperationModel operation, int statusCode,
                                         ValidationResult result) {
        String exact = String.valueOf(statusCode);
        ResponseModel response = operation.getResponses().get(exact);
        if (response != null) {
            return response;
        }
        String range = (statusCode / 100) + "XX";
        response = operation.getResponses().get(range);
        if (response != null) {
            return response;
        }
        response = operation.getResponses().get("default");
        if (response != null) {
            result.addIssue("matched via 'default' fallback for status " + exact
                    + ": precise response definition missing");
            return response;
        }
        result.setComplete(false);
        result.addIssue("no response definition for status " + exact
                + " and no default fallback");
        return null;
    }

    private ESchema selectMediaType(java.util.TreeMap<String, ESchema> mediaTypes,
                                    String requested, ValidationResult result) {
        if (mediaTypes.isEmpty()) {
            result.addIssue("no content media types defined for " + requested);
            return null;
        }
        ESchema schema = mediaTypes.get(requested);
        if (schema == null) {
            String base = requested.contains(";")
                    ? requested.substring(0, requested.indexOf(';')).trim() : requested;
            schema = mediaTypes.get(base);
        }
        if (schema == null) {
            String wildcard = requested.contains("/")
                    ? requested.substring(0, requested.indexOf('/')) + "/*" : requested;
            schema = mediaTypes.get(wildcard);
        }
        if (schema == null) {
            schema = mediaTypes.get("application/json");
            if (schema != null) {
                result.addIssue("media type " + requested
                        + " not declared; validated as application/json");
            }
        }
        if (schema == null) {
            result.addIssue("media type " + requested + " not declared for response/request");
        }
        return schema;
    }

    private void validateNode(JsonNode node, ESchema schema, String location,
                              ValidationResult result, Set<String> branchStack) {
        if (schema.hasBlockingMarker()) {
            String marker = schema.getMarkers().containsKey("CYCLE")
                    ? StructuralEvidence.CYCLE : StructuralEvidence.UNRESOLVABLE_REF;
            result.setComplete(false);
            result.addEvidence(marker, location + " -> " + schema.getRef());
            return;
        }
        if (node == null || node.isNull()) {
            if (!schema.isNullable()) {
                result.addIssue(location + ": null value for non-nullable schema");
            }
            return;
        }
        if (!schema.getEnumValues().isEmpty() && !schema.getEnumValues().contains(node.asText())) {
            result.addIssue(location + ": value '" + node.asText()
                    + "' not in enum " + schema.getEnumValues());
        }
        String type = schema.getType();
        if (type != null) {
            validateType(node, schema, type, location, result, branchStack);
        }
        validateComposed(node, schema, location, result, branchStack);
    }

    private void validateType(JsonNode node, ESchema schema, String type, String location,
                              ValidationResult result, Set<String> branchStack) {
        switch (type) {
            case "object" -> validateObject(node, schema, location, result, branchStack);
            case "array" -> validateArray(node, schema, location, result, branchStack);
            case "string" -> {
                if (!node.isTextual()) {
                    result.addIssue(location + ": expected string");
                } else {
                    String value = node.asText();
                    if (schema.getMinLength() != null && value.length() < schema.getMinLength()) {
                        result.addIssue(location + ": shorter than minLength "
                                + schema.getMinLength());
                    }
                    if (schema.getMaxLength() != null && value.length() > schema.getMaxLength()) {
                        result.addIssue(location + ": longer than maxLength "
                                + schema.getMaxLength());
                    }
                    if (schema.getPattern() != null && !value.matches(schema.getPattern())) {
                        result.addIssue(location + ": does not match pattern "
                                + schema.getPattern());
                    }
                }
            }
            case "integer" -> {
                if (!node.isIntegralNumber()) {
                    result.addIssue(location + ": expected integer");
                } else {
                    validateNumber(node.asDouble(), schema, location, result);
                }
            }
            case "number" -> {
                if (!node.isNumber()) {
                    result.addIssue(location + ": expected number");
                } else {
                    validateNumber(node.asDouble(), schema, location, result);
                }
            }
            case "boolean" -> {
                if (!node.isBoolean()) {
                    result.addIssue(location + ": expected boolean");
                }
            }
            default -> {
                // Unknown/unsupported type: permissive, no pass-rate impact.
            }
        }
    }

    private void validateNumber(double value, ESchema schema, String location,
                                ValidationResult result) {
        if (schema.getMinimum() != null) {
            boolean exclusive = Boolean.TRUE.equals(schema.getExclusiveMinimum());
            if (exclusive ? value <= schema.getMinimum() : value < schema.getMinimum()) {
                result.addIssue(location + ": below minimum " + schema.getMinimum());
            }
        }
        if (schema.getMaximum() != null) {
            boolean exclusive = Boolean.TRUE.equals(schema.getExclusiveMaximum());
            if (exclusive ? value >= schema.getMaximum() : value > schema.getMaximum()) {
                result.addIssue(location + ": above maximum " + schema.getMaximum());
            }
        }
    }

    private void validateObject(JsonNode node, ESchema schema, String location,
                                ValidationResult result, Set<String> branchStack) {
        if (!node.isObject()) {
            result.addIssue(location + ": expected object");
            return;
        }
        for (String requiredName : schema.getRequired().keySet()) {
            if (!node.has(requiredName) || node.get(requiredName).isNull()) {
                result.addIssue(location + "." + requiredName + ": required field missing");
            }
        }
        List<String> fieldNames = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(fieldNames::add);
        java.util.Collections.sort(fieldNames);
        boolean closed = schema.getAdditionalProperties() != null
                && schema.getAdditionalProperties().getMarkers().containsKey("CLOSED");
        for (String fieldName : fieldNames) {
            ESchema property = schema.getProperties().get(fieldName);
            String child = location + "." + fieldName;
            if (property != null) {
                validateNode(node.get(fieldName), property, child, result, branchStack);
            } else if (closed) {
                result.addIssue(child + ": field not allowed (additionalProperties=false)");
            } else if (schema.getAdditionalProperties() != null
                    && !schema.getAdditionalProperties().getMarkers().containsKey("CLOSED")) {
                validateNode(node.get(fieldName), schema.getAdditionalProperties(), child,
                        result, branchStack);
            }
        }
    }

    private void validateArray(JsonNode node, ESchema schema, String location,
                               ValidationResult result, Set<String> branchStack) {
        if (!node.isArray()) {
            result.addIssue(location + ": expected array");
            return;
        }
        if (schema.getMinItems() != null && node.size() < schema.getMinItems()) {
            result.addIssue(location + ": fewer than minItems " + schema.getMinItems());
        }
        if (schema.getMaxItems() != null && node.size() > schema.getMaxItems()) {
            result.addIssue(location + ": more than maxItems " + schema.getMaxItems());
        }
        if (schema.getItems() != null) {
            for (int i = 0; i < node.size(); i++) {
                validateNode(node.get(i), schema.getItems(), location + "[" + i + "]",
                        result, branchStack);
            }
        }
    }

    private void validateComposed(JsonNode node, ESchema schema, String location,
                                  ValidationResult result, Set<String> branchStack) {
        List<ESchema> branches = !schema.getOneOf().isEmpty()
                ? schema.getOneOf() : schema.getAnyOf();
        if (branches.isEmpty()) {
            return;
        }
        List<ESchema> candidates = branches;
        String discriminator = schema.getDiscriminatorProperty();
        if (discriminator != null && node.isObject() && node.has(discriminator)) {
            String value = node.get(discriminator).asText();
            String mapping = schema.getDiscriminatorMapping().get(value);
            ESchema mapped = matchByMapping(branches, mapping);
            if (mapped != null) {
                candidates = List.of(mapped);
            }
        }
        ValidationResult best = null;
        for (ESchema branch : candidates) {
            ValidationResult attempt = new ValidationResult();
            validateNode(node, branch, location, attempt, new HashSet<>(branchStack));
            if (attempt.isValid() || best == null
                    || attempt.getIssues().size() < best.getIssues().size()) {
                best = attempt;
            }
            if (attempt.isValid()) {
                break;
            }
        }
        if (best != null && !best.isValid()) {
            for (String issue : best.getIssues()) {
                result.addIssue(location + " (composition): " + issue);
            }
        }
    }

    private ESchema matchByMapping(List<ESchema> branches, String mapping) {
        if (mapping == null) {
            return null;
        }
        for (ESchema branch : branches) {
            if (mapping.equals(branch.getRef())) {
                return branch;
            }
        }
        return null;
    }
}
