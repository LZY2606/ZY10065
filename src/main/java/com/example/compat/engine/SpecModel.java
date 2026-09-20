package com.example.compat.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Comparator;
import java.util.TreeMap;

/** Parsed OpenAPI document plus independent structural evidence. */
public final class SpecModel {
    private final String openapi;
    private final TreeMap<String, OperationModel> operations;
    private final RefResolver resolver;
    private final JsonNode root;

    public SpecModel(String openapi, TreeMap<String, OperationModel> operations,
                     RefResolver resolver, JsonNode root) {
        this.openapi = openapi;
        this.operations = operations;
        this.resolver = resolver;
        this.root = root;
    }

    public String getOpenapi() { return openapi; }
    public TreeMap<String, OperationModel> getOperations() { return operations; }
    public RefResolver getResolver() { return resolver; }
    public JsonNode getRoot() { return root; }

    /** "METHOD path" deterministic key. */
    public static String opKey(String method, String path) {
        return method.toUpperCase() + " " + path;
    }

    public java.util.List<OperationModel> sortedOperations() {
        return operations.values().stream()
                .sorted(Comparator.comparing(OperationModel::getPath)
                        .thenComparing(op -> op.getMethod().toUpperCase()))
                .toList();
    }
}
