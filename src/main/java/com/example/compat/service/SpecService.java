package com.example.compat.service;

import com.example.compat.engine.Canonicalizer;
import com.example.compat.engine.SpecModel;
import com.example.compat.engine.SpecParser;
import com.example.compat.repo.Repositories;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SpecService {

    private final Repositories repo;
    private final SpecParser parser = new SpecParser();

    public SpecService(Repositories repo) {
        this.repo = repo;
    }

    /**
     * Idempotent import: identity is the canonical-content fingerprint.
     * Re-importing the same document returns the existing row and "deduplicated=true".
     */
    public synchronized Map<String, Object> importSpec(String name, String role,
                                                       String mediaType, String content) {
        JsonNode root = Canonicalizer.parse(mediaType, content);
        String fingerprint = Canonicalizer.fingerprint(root);
        Map<String, Object> existing = repo.findSpecByFingerprint(fingerprint);
        boolean deduplicated;
        long id;
        if (existing != null) {
            id = ((Number) existing.get("id")).longValue();
            deduplicated = true;
        } else {
            SpecModel parsed = parser.parse(root);
            long now = System.currentTimeMillis();
            id = repo.insertSpec(name, role, fingerprint, mediaType,
                    Canonicalizer.canonical(root), now);
            deduplicated = false;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", name);
        result.put("role", role);
        result.put("fingerprint", fingerprint);
        result.put("deduplicated", deduplicated);
        return result;
    }

    public SpecModel loadParsed(long specId) {
        Map<String, Object> row = repo.findSpecById(specId);
        if (row == null) {
            throw new IllegalArgumentException("spec not found: " + specId);
        }
        String content = (String) row.get("content");
        JsonNode root = Canonicalizer.parse("application/json", content);
        return parser.parse(root);
    }

    public List<Map<String, Object>> list() {
        return repo.listSpecs();
    }

    public Map<String, Object> get(long id) {
        return repo.findSpecById(id);
    }
}
