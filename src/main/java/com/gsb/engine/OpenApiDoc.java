package com.gsb.engine;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record OpenApiDoc(Map<String, OperationInfo> operations, List<ParseIssue> issues) {
    public OpenApiDoc {
        Map<String, OperationInfo> sorted = new TreeMap<>(operations);
        operations = sorted;
    }
}
