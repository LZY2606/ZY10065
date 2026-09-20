package com.gsb.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsb.model.RunReport;
import com.gsb.service.RunService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RunService runs;

    public RunController(RunService runs) {
        this.runs = runs;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Dtos.RunBody body) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        return detail(runs.createOrFetch(body.toRequest()));
    }

    @GetMapping("/{runKey}")
    public Map<String, Object> get(@PathVariable String runKey) {
        return detail(runs.require(runKey));
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return runs.list().stream().map(RunController::summary).toList();
    }

    private static Map<String, Object> summary(RunReport report) {
        Map<String, Object> map = new TreeMap<>();
        map.put("candidateHash", report.getCandidateHash());
        map.put("candidateKey", report.getCandidateKey());
        map.put("createdAt", report.getCreatedAt() == null ? "" : report.getCreatedAt().toString());
        map.put("policyVersion", report.getPolicyVersion());
        map.put("runKey", report.getRunKey());
        map.put("sampleSet", report.getSampleSet());
        map.put("sampleSetHash", report.getSampleSetHash());
        map.put("status", report.getStatus());
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(RunReport report) {
        Map<String, Object> map = new LinkedHashMap<>(summary(report));
        try {
            map.put("report", MAPPER.readValue(report.getReportJson(), Map.class));
        } catch (Exception e) {
            map.put("report", Map.of());
        }
        return map;
    }
}
