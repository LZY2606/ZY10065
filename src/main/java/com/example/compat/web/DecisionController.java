package com.example.compat.web;

import com.example.compat.service.ExemptionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DecisionController {

    private final ExemptionService exemptions;

    public DecisionController(ExemptionService exemptions) {
        this.exemptions = exemptions;
    }

    @PostMapping("/runs/{id}/exemptions")
    public Map<String, Object> grant(@PathVariable long id, @RequestBody JsonNode body) {
        return exemptions.grantExemption(id,
                requireText(body, "findingId"),
                requireText(body, "reason"),
                requireText(body, "actor"),
                requireLong(body, "validFromMs"),
                requireLong(body, "validToMs"));
    }

    @PostMapping("/exemptions/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable long id, @RequestBody JsonNode body) {
        return exemptions.revokeExemption(id,
                requireText(body, "reason"),
                requireText(body, "actor"));
    }

    @GetMapping("/exemptions")
    public List<Map<String, Object>> listExemptions() {
        return exemptions.listAll();
    }

    @org.springframework.web.bind.annotation.PostMapping("/exemptions/{id}/reconcile")
    public Map<String, Object> reconcile(@PathVariable long id,
                                         @org.springframework.web.bind.annotation.RequestParam
                                         long candidateSpecId,
                                         @org.springframework.web.bind.annotation.RequestParam(required = false)
                                         Long asOfMs) {
        return exemptions.reconcileAgainst(id, candidateSpecId, asOfMs);
    }

    @PostMapping("/runs/{id}/decisions")
    public Map<String, Object> decide(@PathVariable long id, @RequestBody JsonNode body) {
        return exemptions.setDecision(id,
                requireText(body, "findingId"),
                requireText(body, "disposition"),
                requireText(body, "reason"),
                requireText(body, "actor"));
    }

    @GetMapping("/events")
    public Object events(@RequestParam(required = false) Long runId) {
        return exemptions.events(runId);
    }

    private String requireText(JsonNode body, String field) {
        if (!body.has(field) || !body.get(field).isTextual()
                || body.get(field).asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return body.get(field).asText();
    }

    private long requireLong(JsonNode body, String field) {
        if (!body.has(field) || !body.get(field).isNumber()) {
            throw new IllegalArgumentException(field + " is required (epoch millis)");
        }
        return body.get(field).asLong();
    }
}
