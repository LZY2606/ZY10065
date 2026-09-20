package com.example.compat.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Determinism foundation: all fingerprints are SHA-256 over canonical JSON.
 * Canonical JSON sorts object keys recursively and has no insignificant whitespace,
 * so YAML vs JSON formatting and key insertion order can never change results.
 */
public final class Canonicalizer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML = new YAMLMapper();

    private Canonicalizer() {
    }

    /** Parses JSON or YAML content based on the declared media type. */
    public static JsonNode parse(String mediaType, String content) {
        try {
            if (mediaType != null && mediaType.contains("yaml")) {
                return YAML.readTree(content);
            }
            return JSON.readTree(content);
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot parse document as " + mediaType + ": " + e.getMessage(), e);
        }
    }

    public static String canonical(JsonNode node) {
        try {
            return JSON.writeValueAsString(toSorted(node));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String fingerprint(JsonNode node) {
        return sha256(canonical(node));
    }

    /** Recursively sorts object keys so fingerprints are independent of traversal/parse order. */
    private static Object toSorted(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                sorted.put(field.getKey(), toSorted(field.getValue()));
            }
            return sorted;
        }
        if (node.isArray()) {
            java.util.ArrayList<Object> list = new java.util.ArrayList<>(node.size());
            for (JsonNode item : node) {
                list.add(toSorted(item));
            }
            return list;
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.asText();
    }
}
