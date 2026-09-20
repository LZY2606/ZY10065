package com.gsb.engine;

import java.util.Map;

public record MediaBody(Map<String, SchemaNode> content, boolean required) {
    public static final MediaBody ABSENT = new MediaBody(Map.of(), false);

    public SchemaNode jsonSchema() {
        SchemaNode node = content.get("application/json");
        if (node == null) {
            for (Map.Entry<String, SchemaNode> e : content.entrySet()) {
                if (e.getKey().contains("json")) {
                    return e.getValue();
                }
            }
        }
        return node;
    }
}
