package com.gsb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.gsb.engine.Canonical;
import com.gsb.model.SampleRecord;
import com.gsb.model.SampleRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sample import. A batch is all-or-nothing: any malformed entry rejects the
 * whole transaction so no partial results ever become visible.
 */
@Service
public class SampleService {

    private final SampleRecordRepository samples;

    public SampleService(SampleRecordRepository samples) {
        this.samples = samples;
    }

    public record ImportResult(int received, int inserted, int skipped, int rejected) {
    }

    @Transactional
    public ImportResult importBatch(String set, List<Map<String, Object>> rows) {
        String sampleSet = normalizeSet(set);
        if (rows == null) {
            throw new IllegalArgumentException("sample array is required");
        }
        List<SampleRecord> parsed = new ArrayList<>(rows.size());
        Map<String, Integer> seenInBatch = new LinkedHashMap<>();
        int index = 0;
        for (Map<String, Object> row : rows) {
            index++;
            if (row == null) {
                throw new BatchRejectedException("sample #" + index + " is null");
            }
            String method = str(row.get("method"));
            String path = str(row.get("path"));
            if (method == null || method.isBlank()) {
                throw new BatchRejectedException("sample #" + index + " missing method");
            }
            if (path == null || path.isBlank()) {
                throw new BatchRejectedException("sample #" + index + " missing path");
            }

            String reqMedia = str(row.get("requestMediaType"));
            String respMedia = str(row.get("responseMediaType"));
            Integer status = intOrNull(row.get("statusCode"), index);
            String reqBody = normalizeBody(row.get("requestBody"));
            String respBody = normalizeBody(row.get("responseBody"));

            SampleRecord rec = new SampleRecord();
            rec.setSampleSet(sampleSet);
            rec.setMethod(method.trim().toUpperCase());
            rec.setPath(path.trim());
            rec.setRequestMediaType(reqMedia);
            rec.setRequestBody(reqBody);
            rec.setStatusCode(status);
            rec.setResponseMediaType(respMedia);
            rec.setResponseBody(respBody);
            String explicit = str(row.get("completeness"));
            if (explicit != null) {
                if (!Set.of(SampleRecord.COMPLETE, SampleRecord.INCOMPLETE_REQUEST,
                        SampleRecord.INCOMPLETE_RESPONSE, SampleRecord.EMPTY).contains(explicit)) {
                    throw new BatchRejectedException(
                            "sample #" + index + " has invalid completeness: " + explicit);
                }
                rec.setCompleteness(explicit);
            } else {
                rec.setCompleteness(completeness(reqBody, respBody));
            }
            rec.setDedupKey(dedupKey(sampleSet, rec));

            if (seenInBatch.containsKey(rec.getDedupKey())) {
                throw new BatchRejectedException(
                        "sample #" + index + " duplicates sample #"
                                + seenInBatch.get(rec.getDedupKey()) + " in the same batch");
            }
            seenInBatch.put(rec.getDedupKey(), index);
            parsed.add(rec);
        }

        int inserted = 0;
        int skipped = 0;
        for (SampleRecord rec : parsed) {
            if (samples.findByDedupKey(rec.getDedupKey()).isPresent()) {
                skipped++;
                continue;
            }
            samples.save(rec);
            inserted++;
        }
        return new ImportResult(parsed.size(), inserted, skipped, 0);
    }

    @Transactional(readOnly = true)
    public List<SampleRecord> list(String set) {
        if (set == null || set.isBlank()) {
            return samples.findAllByOrderByIdAsc();
        }
        return samples.findBySampleSetOrderByIdAsc(normalizeSet(set));
    }

    @Transactional
    public long deleteSet(String set) {
        String sampleSet = normalizeSet(set);
        long n = samples.countBySampleSet(sampleSet);
        samples.deleteBySampleSet(sampleSet);
        return n;
    }

    private static String normalizeSet(String set) {
        if (set == null || set.isBlank()) {
            return "default";
        }
        return set.trim();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Integer intOrNull(Object o, int index) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            throw new BatchRejectedException("sample #" + index + " has invalid statusCode");
        }
    }

    /**
     * Bodies are parsed when possible and re-serialized canonically, so
     * whitespace/key-order differences never create distinct samples.
     */
    private static String normalizeBody(Object body) {
        if (body == null) {
            return null;
        }
        if (body instanceof String s) {
            if (s.isBlank()) {
                return null;
            }
            Object parsed = tryParse(s);
            if (parsed != null) {
                return Json.write(parsed);
            }
            return s;
        }
        return Json.write(body);
    }

    private static Object tryParse(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return null;
        }
        try {
            JsonNode node = Json.MAPPER.readTree(trimmed);
            return Json.MAPPER.treeToValue(node, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A replayable sample centers on the observed response: a captured GET has
     * no request body but is still COMPLETE. Missing the response means the
     * consumer-facing half cannot be judged; having neither body is unusable.
     * Callers may also pass "completeness" explicitly to mark partial captures.
     */
    private static String completeness(String reqBody, String respBody) {
        if (respBody != null) {
            return SampleRecord.COMPLETE;
        }
        return reqBody != null ? SampleRecord.INCOMPLETE_RESPONSE : SampleRecord.EMPTY;
    }

    private static String dedupKey(String set, SampleRecord rec) {
        List<Object> tuple = List.of(
                set,
                rec.getMethod(),
                rec.getPath(),
                nz(rec.getRequestMediaType()),
                nz(rec.getRequestBody()),
                rec.getStatusCode() == null ? "" : rec.getStatusCode(),
                nz(rec.getResponseMediaType()),
                nz(rec.getResponseBody()));
        return Canonical.sha256(tuple);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public static class BatchRejectedException extends RuntimeException {
        public BatchRejectedException(String message) {
            super(message);
        }
    }
}
