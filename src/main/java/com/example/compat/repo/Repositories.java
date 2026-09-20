package com.example.compat.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Thin data-access layer. Semantic identity is always a content fingerprint. */
@Repository
public class Repositories {

    private final JdbcTemplate jdbc;

    public Repositories(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /** H2 returns uppercase labels; normalize to stable snake_case API keys. */
    private List<Map<String, Object>> rows(String sql, Object... args) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(sql, args)) {
            Map<String, Object> lower = new LinkedHashMap<>();
            row.forEach((key, value) -> lower.put(key == null ? null : key.toLowerCase(), value));
            normalized.add(lower);
        }
        return normalized;
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> found = rows(sql, args);
        return found.isEmpty() ? null : found.get(0);
    }

    // ---- specs ----
    public Map<String, Object> findSpecByFingerprint(String fingerprint) {
        return one("select * from spec where fingerprint = ?", fingerprint);
    }

    public Map<String, Object> findSpecById(long id) {
        return one("select * from spec where id = ?", id);
    }

    public List<Map<String, Object>> listSpecs() {
        return rows("select * from spec order by id");
    }

    public long insertSpec(String name, String role, String fingerprint,
                           String mediaType, String content, long nowMs) {
        jdbc.update("insert into spec(name, role, fingerprint, media_type, content, created_at_ms)"
                + " values (?, ?, ?, ?, ?, ?)",
                name, role, fingerprint, mediaType, content, nowMs);
        return jdbc.queryForObject("select id from spec where fingerprint = ?",
                Long.class, fingerprint);
    }

    // ---- sample batches ----
    public Map<String, Object> findBatchBySnapshot(String snapshotFingerprint) {
        return one("select * from sample_batch where snapshot_fingerprint = ?",
                snapshotFingerprint);
    }

    public Map<String, Object> findBatchById(long id) {
        return one("select * from sample_batch where id = ?", id);
    }

    public List<Map<String, Object>> listBatches() {
        return rows("select * from sample_batch order by id");
    }

    public long insertBatch(String name, String snapshotFingerprint, int sampleCount,
                            long nowMs) {
        jdbc.update("insert into sample_batch(name, snapshot_fingerprint, sample_count,"
                + " created_at_ms) values (?, ?, ?, ?)",
                name, snapshotFingerprint, sampleCount, nowMs);
        return jdbc.queryForObject(
                "select id from sample_batch where snapshot_fingerprint = ?",
                Long.class, snapshotFingerprint);
    }

    public void insertSample(long batchId, String key, String contentFingerprint,
                             String payload) {
        jdbc.update("insert into sample(batch_id, sample_key, content_fingerprint, payload)"
                + " values (?, ?, ?, ?)", batchId, key, contentFingerprint, payload);
    }

    public List<Map<String, Object>> listSamples(long batchId) {
        return rows("select * from sample where batch_id = ? order by sample_key", batchId);
    }

    // ---- runs ----
    public Map<String, Object> findRunByFingerprint(String runFingerprint) {
        return one("select * from run where run_fingerprint = ?", runFingerprint);
    }

    public Map<String, Object> findRunById(long id) {
        return one("select * from run where id = ?", id);
    }

    public List<Map<String, Object>> listRuns() {
        return rows("select * from run order by id desc");
    }

    public void insertRun(String runFingerprint, long baselineId, long candidateId,
                          long batchId, String policyVersion,
                          String baselineFingerprint, String candidateFingerprint,
                          String snapshotFingerprint, String report, long nowMs) {
        jdbc.update("insert into run(run_fingerprint, baseline_id, candidate_id,"
                        + " sample_batch_id, policy_version, baseline_fingerprint,"
                        + " candidate_fingerprint, snapshot_fingerprint, report, created_at_ms)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                runFingerprint, baselineId, candidateId, batchId, policyVersion,
                baselineFingerprint, candidateFingerprint, snapshotFingerprint, report, nowMs);
    }

    // ---- events (append only) ----
    public void insertEvent(String eventType, Long exemptionId, Long decisionId, Long runId,
                            String findingId, String method, String path, String changeCode,
                            String reason, String actor, String beforeVersion,
                            String afterVersion, Long validFromMs, Long validToMs,
                            String disposition, long nowMs) {
        jdbc.update("insert into event(event_type, exemption_id, decision_id, run_id, finding_id,"
                        + " method, path, change_code, reason, actor, before_version, after_version,"
                        + " valid_from_ms, valid_to_ms, disposition, created_at_ms)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                eventType, exemptionId, decisionId, runId, findingId, method, path, changeCode,
                reason, actor, beforeVersion, afterVersion, validFromMs, validToMs,
                disposition, nowMs);
    }

    public List<Map<String, Object>> listEvents(Long runId) {
        if (runId == null) {
            return rows("select * from event order by id");
        }
        return rows("select * from event where run_id = ? order by id", runId);
    }

    // ---- exemptions ----
    public Map<String, Object> findExemptionByKey(String key) {
        return one("select * from exemption where exemption_key = ?", key);
    }

    public Map<String, Object> findExemptionById(long id) {
        return one("select * from exemption where id = ?", id);
    }

    public List<Map<String, Object>> listExemptions(Long runId) {
        if (runId == null) {
            return rows("select * from exemption order by id desc");
        }
        return rows("select * from exemption where run_id = ? order by id desc", runId);
    }

    public void insertExemption(String key, long runId, String findingId, String method,
                                String path, String changeCode, String anchor, String subject,
                                String candidateFingerprint, String baselineFingerprint,
                                long validFromMs, long validToMs, String reason, String actor,
                                long grantedEventId, long nowMs) {
        jdbc.update("insert into exemption(exemption_key, run_id, finding_id, method, path,"
                        + " change_code, anchor, subject, bound_candidate_fingerprint,"
                        + " baseline_fingerprint, valid_from_ms, valid_to_ms, status, reason,"
                        + " actor, granted_event_id, created_at_ms, updated_at_ms)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)",
                key, runId, findingId, method, path, changeCode, anchor, subject,
                candidateFingerprint, baselineFingerprint, validFromMs, validToMs,
                reason, actor, grantedEventId, nowMs, nowMs);
    }

    public void markExemption(long id, String status, long revokedEventId, long nowMs) {
        jdbc.update("update exemption set status = ?, revoked_event_id = ?, updated_at_ms = ?"
                + " where id = ?", status, revokedEventId, nowMs, id);
    }

    // ---- per-finding manual decisions ----
    public Map<String, Object> findDecisionByKey(String key) {
        return one("select * from decision where decision_key = ?", key);
    }

    public void insertDecision(String key, long runId, String findingId, String disposition,
                               String reason, String actor, long nowMs) {
        jdbc.update("insert into decision(decision_key, run_id, finding_id, disposition, reason,"
                        + " actor, active, created_at_ms, updated_at_ms)"
                        + " values (?, ?, ?, ?, ?, ?, true, ?, ?)",
                key, runId, findingId, disposition, reason, actor, nowMs, nowMs);
    }

    public void deactivateDecision(long id, long nowMs) {
        jdbc.update("update decision set active = false, updated_at_ms = ? where id = ?",
                nowMs, id);
    }

    public List<Map<String, Object>> listDecisions(Long runId) {
        if (runId == null) {
            return rows("select * from decision order by id desc");
        }
        return rows("select * from decision where run_id = ? order by id desc", runId);
    }
}
