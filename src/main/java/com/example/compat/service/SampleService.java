package com.example.compat.service;

import com.example.compat.engine.Canonicalizer;
import com.example.compat.engine.SampleModel;
import com.example.compat.repo.Repositories;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SampleService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Repositories repo;

    public SampleService(Repositories repo) {
        this.repo = repo;
    }

    /**
     * Atomically imports a named sample-set snapshot.
     *
     * The snapshot fingerprint covers the whole ordered set, so identical
     * re-import returns the existing batch. Within a batch, duplicated keys and
     * duplicated sample contents are rejected, which rolls the whole batch back:
     * a failed batch never leaves visible partial results.
     */
    @Transactional
    public synchronized Map<String, Object> importBatch(String name, JsonNode payload) {
        if (payload == null || !payload.isArray()) {
            throw new IllegalArgumentException("sample payload must be a JSON array");
        }
        List<JsonNode> samples = new ArrayList<>();
        payload.forEach(samples::add);
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("sample batch must contain at least one sample");
        }
        Set<String> keysSeen = new HashSet<>();
        Set<String> contentsSeen = new HashSet<>();
        List<SampleModel> parsed = new ArrayList<>();
        List<String> rawPayloads = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            JsonNode sample = samples.get(i);
            SampleModel model = toModel(sample, i);
            if (!keysSeen.add(model.key())) {
                throw new IllegalArgumentException("duplicate sample key in batch: " + model.key());
            }
            String contentFingerprint = Canonicalizer.fingerprint(sample);
            if (!contentsSeen.add(contentFingerprint)) {
                throw new IllegalArgumentException(
                        "duplicate sample content in batch at index " + i);
            }
            parsed.add(model);
            rawPayloads.add(Canonicalizer.canonical(sample));
        }
        String snapshotFingerprint = Canonicalizer.fingerprint(MAPPER.valueToTree(rawPayloads));
        Map<String, Object> existing = repo.findBatchBySnapshot(snapshotFingerprint);
        if (existing != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", ((Number) existing.get("id")).longValue());
            result.put("name", existing.get("name"));
            result.put("snapshotFingerprint", snapshotFingerprint);
            result.put("sampleCount", parsed.size());
            result.put("deduplicated", true);
            return result;
        }
        long now = System.currentTimeMillis();
        long batchId = repo.insertBatch(name, snapshotFingerprint, parsed.size(), now);
        for (int i = 0; i < parsed.size(); i++) {
            SampleModel model = parsed.get(i);
            repo.insertSample(batchId, model.key(),
                    Canonicalizer.sha256(rawPayloads.get(i)), rawPayloads.get(i));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", batchId);
        result.put("name", name);
        result.put("snapshotFingerprint", snapshotFingerprint);
        result.put("sampleCount", parsed.size());
        result.put("deduplicated", false);
        return result;
    }

    public List<SampleModel> loadSamples(long batchId) {
        List<SampleModel> models = new ArrayList<>();
        for (Map<String, Object> row : repo.listSamples(batchId)) {
            String payload = (String) row.get("payload");
            try {
                models.add(toModel(MAPPER.readTree(payload), 0));
            } catch (Exception e) {
                throw new IllegalStateException("stored sample unreadable: " + row.get("id"), e);
            }
        }
        return models;
    }

    public List<Map<String, Object>> listBatches() {
        return repo.listBatches();
    }

    private SampleModel toModel(JsonNode node, int index) {
        if (!node.has("key") || !node.has("method") || !node.has("path")) {
            throw new IllegalArgumentException(
                    "sample at index " + index + " requires key, method and path");
        }
        return new SampleModel(
                node.get("key").asText(),
                node.get("method").asText().toUpperCase(),
                node.get("path").asText(),
                node.has("statusCode") && node.get("statusCode").isNumber()
                        ? node.get("statusCode").asInt() : null,
                text(node, "requestMediaType"),
                text(node, "responseMediaType"),
                node.get("requestBody"),
                node.get("responseBody"));
    }

    private String text(JsonNode node, String field) {
        return node.has(field) && node.get(field).isTextual() ? node.get(field).asText() : null;
    }
}
