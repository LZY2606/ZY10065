package com.example.compat.web;

import com.example.compat.service.SpecService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/specs")
public class SpecController {

    private final SpecService specs;

    public SpecController(SpecService specs) {
        this.specs = specs;
    }

    @GetMapping
    public Object list() {
        return specs.list();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        Map<String, Object> spec = specs.get(id);
        if (spec == null) {
            throw new IllegalArgumentException("spec not found: " + id);
        }
        return spec;
    }

    /**
     * Accepts either raw JSON/YAML content (with name/role/mediaType headers-as-params)
     * or an envelope {"name","role","mediaType","content"}.
     */
    @PostMapping
    public Map<String, Object> importSpec(@RequestBody(required = false) JsonNode body,
                                          org.springframework.web.context.request.WebRequest request) {
        if (body == null) {
            throw new IllegalArgumentException("request body required");
        }
        String name;
        String role;
        String mediaType;
        String content;
        if (body.has("content") && body.get("content").isTextual()) {
            name = text(body, "name", "spec");
            role = text(body, "role", "candidate");
            mediaType = text(body, "mediaType", "application/json");
            content = body.get("content").asText();
        } else {
            name = param(request, "name", "spec");
            role = param(request, "role", "candidate");
            mediaType = param(request, "mediaType", "application/json");
            content = body.toString();
        }
        if (!"baseline".equals(role) && !"candidate".equals(role)) {
            throw new IllegalArgumentException("role must be baseline or candidate");
        }
        return specs.importSpec(name, role, mediaType, content);
    }

    private String text(JsonNode node, String field, String fallback) {
        return node.has(field) && node.get(field).isTextual() ? node.get(field).asText() : fallback;
    }

    private String param(org.springframework.web.context.request.WebRequest request,
                         String name, String fallback) {
        String value = request.getParameter(name);
        return value == null ? fallback : value;
    }
}
