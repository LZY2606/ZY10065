package com.example.compat.web;

import com.example.compat.service.SampleService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/sample-batches")
public class SampleController {

    private final SampleService samples;

    public SampleController(SampleService samples) {
        this.samples = samples;
    }

    @GetMapping
    public Object list() {
        return samples.listBatches();
    }

    /** Envelope: {"name": "...", "samples": [ ... ]}. Whole batch is atomic. */
    @PostMapping
    public Map<String, Object> importBatch(@RequestBody JsonNode body,
                                           @RequestParam(defaultValue = "batch") String name) {
        if (!body.isArray() && (!body.has("samples") || !body.get("samples").isArray())) {
            throw new IllegalArgumentException(
                    "body must be a samples array or {\"name\", \"samples\"}");
        }
        String batchName = body.has("name") && body.get("name").isTextual()
                ? body.get("name").asText() : name;
        JsonNode samplesNode = body.isArray() ? body : body.get("samples");
        return samples.importBatch(batchName, samplesNode);
    }
}
