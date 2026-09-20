package com.gsb.web;

import com.gsb.model.SampleRecord;
import com.gsb.service.SampleService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@RestController
@RequestMapping("/api/samples")
public class SampleController {

    private final SampleService samples;

    public SampleController(SampleService samples) {
        this.samples = samples;
    }

    @PostMapping("/{set}")
    public Map<String, Object> post(@PathVariable String set,
                                    @RequestBody List<Map<String, Object>> batch) {
        SampleService.ImportResult result = samples.importBatch(set, batch);
        Map<String, Object> map = new TreeMap<>();
        map.put("inserted", result.inserted());
        map.put("received", result.received());
        map.put("rejected", result.rejected());
        map.put("skipped", result.skipped());
        return map;
    }

    @GetMapping("/{set}")
    public List<Map<String, Object>> list(@PathVariable String set) {
        return samples.list(set).stream().map(SampleController::toMap).toList();
    }

    @DeleteMapping("/{set}")
    public Map<String, Object> delete(@PathVariable String set) {
        long removed = samples.deleteSet(set);
        Map<String, Object> map = new TreeMap<>();
        map.put("deleted", removed);
        map.put("sampleSet", set);
        return map;
    }

    private static Map<String, Object> toMap(SampleRecord rec) {
        Map<String, Object> map = new TreeMap<>();
        map.put("completeness", rec.getCompleteness());
        map.put("dedupKey", rec.getDedupKey());
        map.put("method", rec.getMethod());
        map.put("path", rec.getPath());
        map.put("requestBody", rec.getRequestBody() == null ? "" : rec.getRequestBody());
        map.put("requestMediaType",
                rec.getRequestMediaType() == null ? "" : rec.getRequestMediaType());
        map.put("responseBody", rec.getResponseBody() == null ? "" : rec.getResponseBody());
        map.put("responseMediaType",
                rec.getResponseMediaType() == null ? "" : rec.getResponseMediaType());
        map.put("sampleSet", rec.getSampleSet());
        map.put("statusCode", rec.getStatusCode() == null ? "" : rec.getStatusCode());
        return map;
    }
}
