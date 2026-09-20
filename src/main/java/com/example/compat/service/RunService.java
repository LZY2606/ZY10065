package com.example.compat.service;

import com.example.compat.engine.Canonicalizer;
import com.example.compat.engine.CompatibilityPolicy;
import com.example.compat.engine.PolicyRegistry;
import com.example.compat.engine.ReportEngine;
import com.example.compat.engine.SampleModel;
import com.example.compat.engine.SpecModel;
import com.example.compat.repo.Repositories;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RunService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Repositories repo;
    private final SpecService specService;
    private final SampleService sampleService;

    public RunService(Repositories repo, SpecService specService, SampleService sampleService) {
        this.repo = repo;
        this.specService = specService;
        this.sampleService = sampleService;
    }

    /**
     * A rehearsal is fully identified by (baseline fingerprint, candidate
     * fingerprint, sample-set snapshot fingerprint, policy version). That tuple
     * hashed into run_fingerprint is the cache key: parallel candidate versions
     * have distinct candidate fingerprints and therefore never share a report.
     */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> createRun(long baselineId, long candidateId,
                                                      long batchId, String policyVersion) {
        CompatibilityPolicy policy = PolicyRegistry.require(policyVersion);
        Map<String, Object> baselineRow = requireSpec(baselineId);
        Map<String, Object> candidateRow = requireSpec(candidateId);
        Map<String, Object> batchRow = repo.findBatchById(batchId);
        if (batchRow == null) {
            throw new IllegalArgumentException("sample batch not found: " + batchId);
        }
        String baselineFingerprint = (String) baselineRow.get("fingerprint");
        String candidateFingerprint = (String) candidateRow.get("fingerprint");
        String snapshotFingerprint = (String) batchRow.get("snapshot_fingerprint");

        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("baselineFingerprint", baselineFingerprint);
        identity.put("candidateFingerprint", candidateFingerprint);
        identity.put("snapshotFingerprint", snapshotFingerprint);
        identity.put("policyVersion", policyVersion);
        String runFingerprint = Canonicalizer.sha256(Canonicalizer.canonical(
                MAPPER.valueToTree(identity)));

        Map<String, Object> existing = repo.findRunByFingerprint(runFingerprint);
        if (existing != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", ((Number) existing.get("id")).longValue());
            result.put("runFingerprint", runFingerprint);
            result.put("deduplicated", true);
            result.put("createdAtMs", existing.get("created_at_ms"));
            return result;
        }

        SpecModel baseline = specService.loadParsed(baselineId);
        SpecModel candidate = specService.loadParsed(candidateId);
        List<SampleModel> samples = sampleService.loadSamples(batchId);
        Map<String, Object> report = new ReportEngine(baseline, candidate, samples, policy)
                .assemble();
        String reportJson = Canonicalizer.canonical(MAPPER.valueToTree(report));
        long now = System.currentTimeMillis();
        repo.insertRun(runFingerprint, baselineId, candidateId, batchId, policyVersion,
                baselineFingerprint, candidateFingerprint, snapshotFingerprint, reportJson, now);
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> inserted = repo.findRunByFingerprint(runFingerprint);
        result.put("id", ((Number) inserted.get("id")).longValue());
        result.put("runFingerprint", runFingerprint);
        result.put("deduplicated", false);
        result.put("createdAtMs", now);
        return result;
    }

    public Map<String, Object> getReport(long runId) {
        Map<String, Object> run = requireRun(runId);
        return parseReport(run);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> parseReport(Map<String, Object> run) {
        try {
            return MAPPER.readValue((String) run.get("report"), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("stored report unreadable", e);
        }
    }

    public Map<String, Object> requireRun(long runId) {
        Map<String, Object> run = repo.findRunById(runId);
        if (run == null) {
            throw new IllegalArgumentException("run not found: " + runId);
        }
        return run;
    }

    public List<Map<String, Object>> list() {
        return repo.listRuns();
    }

    private Map<String, Object> requireSpec(long id) {
        Map<String, Object> row = repo.findSpecById(id);
        if (row == null) {
            throw new IllegalArgumentException("spec not found: " + id);
        }
        return row;
    }
}
