package com.gsb.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsb.model.SpecDocument;
import com.gsb.service.SpecService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@RestController
@RequestMapping("/api/specs")
public class SpecController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SpecService specs;

    public SpecController(SpecService specs) {
        this.specs = specs;
    }

    @PutMapping(value = "/{key}", consumes = MediaType.TEXT_PLAIN_VALUE)
    public Map<String, Object> putPlain(@PathVariable String key, @RequestBody String rawText) {
        return summarize(specs.upsert(key, rawText));
    }

    @PutMapping(value = "/{key}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> putJson(@PathVariable String key,
                                       @RequestBody Dtos.RawTextBody body) {
        if (body == null || body.rawText() == null) {
            throw new IllegalArgumentException("rawText is required");
        }
        return summarize(specs.upsert(key, body.rawText()));
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return specs.list().stream().map(SpecController::summary).toList();
    }

    @GetMapping("/{key}")
    public Map<String, Object> get(@PathVariable String key) {
        return detail(specs.require(key));
    }

    @DeleteMapping("/{key}")
    public Map<String, Object> delete(@PathVariable String key) {
        specs.delete(key);
        Map<String, Object> map = new TreeMap<>();
        map.put("deleted", key);
        return map;
    }

    private static Map<String, Object> summarize(SpecService.UpsertResult result) {
        Map<String, Object> map = summary(result.document());
        map.put("changed", result.changed());
        map.put("created", result.created());
        return map;
    }

    private static Map<String, Object> summary(SpecDocument doc) {
        Map<String, Object> map = new TreeMap<>();
        map.put("contentHash", doc.getContentHash());
        map.put("fingerprint", parseFingerprint(doc.getFingerprintJson()));
        map.put("specKey", doc.getSpecKey());
        map.put("versionSeq", doc.getVersionSeq());
        return map;
    }

    private static Map<String, Object> detail(SpecDocument doc) {
        Map<String, Object> map = summary(doc);
        map.put("rawText", doc.getRawText());
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseFingerprint(String json) {
        try {
            return new TreeMap<>(MAPPER.readValue(json, Map.class));
        } catch (Exception e) {
            return new TreeMap<>();
        }
    }
}
