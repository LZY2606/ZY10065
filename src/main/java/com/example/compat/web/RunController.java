package com.example.compat.web;

import com.example.compat.engine.PolicyRegistry;
import com.example.compat.service.ExemptionService;
import com.example.compat.service.RunService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunService runs;
    private final ExemptionService exemptions;

    public RunController(RunService runs, ExemptionService exemptions) {
        this.runs = runs;
        this.exemptions = exemptions;
    }

    @GetMapping
    public Object list() {
        return runs.list();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody JsonNode body) {
        long baselineId = requireLong(body, "baselineId");
        long candidateId = requireLong(body, "candidateId");
        long sampleBatchId = requireLong(body, "sampleBatchId");
        String policyVersion = body.has("policyVersion") && body.get("policyVersion").isTextual()
                ? body.get("policyVersion").asText() : "compat-1.0";
        return runs.createRun(baselineId, candidateId, sampleBatchId, policyVersion);
    }

    @GetMapping("/{id}/report")
    public Map<String, Object> report(@PathVariable long id,
                                      @RequestParam(required = false) Long asOfMs) {
        Map<String, Object> report = runs.getReport(id);
        Map<String, Object> enriched = new LinkedHashMap<>(report);
        enriched.put("exemptions", exemptions.exemptionsForRun(id, asOfMs));
        enriched.put("decisions", exemptions.decisions(id));
        return enriched;
    }

    @GetMapping("/{id}/samples/{sampleKey}")
    public Map<String, Object> sampleDrilldown(@PathVariable long id,
                                               @PathVariable String sampleKey,
                                               @RequestParam(required = false) Long asOfMs) {
        Map<String, Object> report = runs.getReport(id);
        List<?> samples = (List<?>) report.get("samples");
        Object match = samples.stream()
                .filter(entry -> sampleKey.equals(
                        ((Map<?, ?>) entry).get("key")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "sample " + sampleKey + " not found in run " + id));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", id);
        result.put("sample", match);
        result.put("exemptions", exemptions.exemptionsForRun(id, asOfMs));
        result.put("decisions", exemptions.decisions(id));
        return result;
    }

    @GetMapping("/policies")
    public Map<String, Object> policies() {
        return Map.of("versions", PolicyRegistry.versions());
    }

    private long requireLong(JsonNode body, String field) {
        if (!body.has(field) || !body.get(field).isNumber()) {
            throw new IllegalArgumentException(field + " is required and must be a number");
        }
        return body.get(field).asLong();
    }
}
