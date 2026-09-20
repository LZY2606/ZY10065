package com.example.compat.service;

import com.example.compat.engine.ChangeCodes;

import com.example.compat.engine.Canonicalizer;
import com.example.compat.engine.SpecModel;
import com.example.compat.repo.Repositories;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Time-boxed waivers and per-finding decisions, recorded as an append-only
 * event ledger. Undo/revoke inserts a new event; history is never deleted.
 *
 * A waiver is bound to a structural anchor inside the exact candidate version
 * present at grant time. Re-resolving against a later changed spec that lost
 * the location flips it to PENDING automatically.
 */
@Service
public class ExemptionService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Repositories repo;
    private final RunService runService;
    private final SpecService specService;
    private final ExemptionLocator locator = new ExemptionLocator();

    public ExemptionService(Repositories repo, RunService runService, SpecService specService) {
        this.repo = repo;
        this.runService = runService;
        this.specService = specService;
    }

    @Transactional
    public Map<String, Object> grantExemption(long runId, String findingId, String reason,
                                              String actor, long validFromMs, long validToMs) {
        if (validToMs <= validFromMs) {
            throw new IllegalArgumentException("validTo must be after validFrom");
        }
        requireText(reason, "reason");
        requireText(actor, "actor");
        Map<String, Object> finding = requireFinding(runId, findingId);
        Map<String, Object> run = runService.requireRun(runId);
        long candidateId = ((Number) run.get("candidate_id")).longValue();
        SpecModel candidate = specService.loadParsed(candidateId);

        String method = (String) finding.get("method");
        String path = (String) finding.get("path");
        String anchor = (String) finding.get("anchor");
        String subject = (String) finding.get("subject");
        String code = (String) finding.get("code");
        // A waiver for a removed field binds to the parent object that still exists
        // in the candidate. If that parent later disappears too, resolution fails
        // and the waiver becomes PENDING (no similarity-based reattachment).
        if (ChangeCodes.FIELD_REMOVED.equals(code)
                || ChangeCodes.COMPOSITION_BRANCH_REMOVED.equals(code)) {
            int propertiesIndex = subject.lastIndexOf("/properties/");
            if (propertiesIndex > 0) {
                subject = subject.substring(0, propertiesIndex);
            }
        }
        ExemptionLocator.Result resolution = locator.resolve(candidate, method, path,
                anchor, subject);
        if (resolution.resolution() == ExemptionLocator.Resolution.PENDING) {
            throw new IllegalArgumentException(
                    "cannot grant exemption: " + resolution.detail());
        }

        String baselineFingerprint = (String) run.get("baseline_fingerprint");
        String candidateFingerprint = (String) run.get("candidate_fingerprint");
        String key = Canonicalizer.sha256(String.join("|",
                String.valueOf(runId), findingId, candidateFingerprint));
        if (repo.findExemptionByKey(key) != null) {
            throw new IllegalArgumentException("exemption already granted for finding "
                    + findingId + " on this run/candidate");
        }
        long now = System.currentTimeMillis();
        repo.insertEvent("EXEMPTION_GRANT_REQUESTED", null, null, runId, findingId,
                method, path, code, reason, actor, baselineFingerprint,
                candidateFingerprint, validFromMs, validToMs, "GRANTED", now);
        long eventId = latestEventId();
        repo.insertExemption(key, runId, findingId, method, path, code, anchor, subject,
                candidateFingerprint, baselineFingerprint, validFromMs, validToMs,
                reason, actor, eventId, now);
        long exemptionId = idOf(repo.findExemptionByKey(key));
        Map<String, Object> result = view(repo.findExemptionById(exemptionId), candidate, now);
        result.put("grantEventId", eventId);
        return result;
    }

    @Transactional
    public Map<String, Object> revokeExemption(long exemptionId, String reason, String actor) {
        requireText(reason, "reason");
        requireText(actor, "actor");
        Map<String, Object> exemption = repo.findExemptionById(exemptionId);
        if (exemption == null) {
            throw new IllegalArgumentException("exemption not found: " + exemptionId);
        }
        long now = System.currentTimeMillis();
        repo.insertEvent("EXEMPTION_REVOKED", exemptionId, null,
                ((Number) exemption.get("run_id")).longValue(),
                (String) exemption.get("finding_id"),
                (String) exemption.get("method"), (String) exemption.get("path"),
                (String) exemption.get("change_code"), reason, actor,
                (String) exemption.get("baseline_fingerprint"),
                (String) exemption.get("bound_candidate_fingerprint"),
                ((Number) exemption.get("valid_from_ms")).longValue(),
                ((Number) exemption.get("valid_to_ms")).longValue(),
                "REVOKED", now);
        long eventId = latestEventId();
        repo.markExemption(exemptionId, "REVOKED", eventId, now);
        return view(repo.findExemptionById(exemptionId), null, now);
    }

    @Transactional
    public Map<String, Object> setDecision(long runId, String findingId, String disposition,
                                           String reason, String actor) {
        requireText(reason, "reason");
        requireText(actor, "actor");
        if (!"ACCEPTED".equals(disposition) && !"REJECTED".equals(disposition)) {
            throw new IllegalArgumentException("disposition must be ACCEPTED or REJECTED");
        }
        Map<String, Object> finding = requireFinding(runId, findingId);
        Map<String, Object> run = runService.requireRun(runId);
        String key = Canonicalizer.sha256(String.join("|",
                String.valueOf(runId), findingId));
        Map<String, Object> existing = repo.findDecisionByKey(key);
        long now = System.currentTimeMillis();
        String beforeVersion = existing == null ? null : (String) existing.get("disposition");
        if (existing != null) {
            repo.deactivateDecision(((Number) existing.get("id")).longValue(), now);
        }
        repo.insertEvent("FINDING_DECISION", null,
                existing == null ? null : ((Number) existing.get("id")).longValue(),
                runId, findingId, (String) finding.get("method"),
                (String) finding.get("path"), (String) finding.get("code"),
                reason, actor, beforeVersion, disposition, null, null, disposition, now);
        repo.insertDecision(key, runId, findingId, disposition, reason, actor, now);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("decisionKey", key);
        result.put("runId", runId);
        result.put("findingId", findingId);
        result.put("disposition", disposition);
        result.put("reason", reason);
        result.put("actor", actor);
        return result;
    }

    /**
     * Exemption overlay for a run. asOfMs defaults to the run creation time,
     * so the same saved inputs + rules always reconstruct the same view.
     */
    public List<Map<String, Object>> exemptionsForRun(long runId, Long asOfMs) {
        Map<String, Object> run = runService.requireRun(runId);
        long effectiveAsOf = asOfMs != null ? asOfMs
                : ((Number) run.get("created_at_ms")).longValue();
        long candidateId = ((Number) run.get("candidate_id")).longValue();
        SpecModel latestCandidate = specService.loadParsed(candidateId);
        List<Map<String, Object>> views = new ArrayList<>();
        for (Map<String, Object> exemption : repo.listExemptions(runId)) {
            views.add(view(exemption, latestCandidate, effectiveAsOf));
        }
        return views;
    }

    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> views = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map<String, Object> exemption : repo.listExemptions(null)) {
            SpecModel candidate = null;
            views.add(view(exemption, candidate, now));
        }
        return views;
    }

    /**
     * Re-resolves one exemption against an arbitrary candidate spec id.
     * This is the operator's "does my time-boxed waiver still bind after the spec
     * kept evolving?" check; the answer is PENDING with a structural reason.
     */
    public Map<String, Object> reconcileAgainst(long exemptionId, long candidateSpecId,
                                                Long asOfMs) {
        Map<String, Object> exemption = repo.findExemptionById(exemptionId);
        if (exemption == null) {
            throw new IllegalArgumentException("exemption not found: " + exemptionId);
        }
        SpecModel candidate = specService.loadParsed(candidateSpecId);
        long effectiveAsOf = asOfMs != null ? asOfMs : System.currentTimeMillis();
        Map<String, Object> view = view(exemption, candidate, effectiveAsOf);
        view.put("reconciledCandidateSpecId", candidateSpecId);
        return view;
    }

    public List<Map<String, Object>> events(Long runId) {
        return repo.listEvents(runId);
    }

    public List<Map<String, Object>> decisions(Long runId) {
        return repo.listDecisions(runId);
    }

    private Map<String, Object> view(Map<String, Object> exemption, SpecModel candidate,
                                     long asOfMs) {
        Map<String, Object> view = new LinkedHashMap<>();
        long id = ((Number) exemption.get("id")).longValue();
        long runId = ((Number) exemption.get("run_id")).longValue();
        view.put("id", id);
        view.put("runId", runId);
        view.put("findingId", exemption.get("finding_id"));
        view.put("method", exemption.get("method"));
        view.put("path", exemption.get("path"));
        view.put("changeCode", exemption.get("change_code"));
        view.put("anchor", exemption.get("anchor"));
        view.put("subject", exemption.get("subject"));
        view.put("boundCandidateFingerprint",
                exemption.get("bound_candidate_fingerprint"));
        view.put("validFromMs", exemption.get("valid_from_ms"));
        view.put("validToMs", exemption.get("valid_to_ms"));
        view.put("reason", exemption.get("reason"));
        view.put("actor", exemption.get("actor"));
        String storedStatus = (String) exemption.get("status");
        String effectiveStatus = effectiveStatus(exemption, candidate, asOfMs, storedStatus);
        view.put("status", storedStatus);
        view.put("effectiveStatus", effectiveStatus);
        if (candidate != null && "ACTIVE".equals(storedStatus)) {
            ExemptionLocator.Result result = locator.resolve(candidate,
                    (String) exemption.get("method"), (String) exemption.get("path"),
                    (String) exemption.get("anchor"), (String) exemption.get("subject"));
            view.put("resolution", result.detail());
        }
        return view;
    }

    /**
     * Effective state is a pure re-evaluation:
     * REVOKED stays revoked; out-of-window -> EXPIRED; lost location -> PENDING;
     * otherwise ACTIVE. No string-similarity fallback exists.
     */
    private String effectiveStatus(Map<String, Object> exemption, SpecModel candidate,
                                   long asOfMs, String storedStatus) {
        if ("REVOKED".equals(storedStatus)) {
            return "REVOKED";
        }
        long validFrom = ((Number) exemption.get("valid_from_ms")).longValue();
        long validTo = ((Number) exemption.get("valid_to_ms")).longValue();
        if (asOfMs < validFrom) {
            return "NOT_STARTED";
        }
        if (asOfMs >= validTo) {
            return "EXPIRED";
        }
        if (candidate != null) {
            ExemptionLocator.Result result = locator.resolve(candidate,
                    (String) exemption.get("method"), (String) exemption.get("path"),
                    (String) exemption.get("anchor"), (String) exemption.get("subject"));
            if (result.resolution() == ExemptionLocator.Resolution.PENDING) {
                return "PENDING";
            }
        }
        return "ACTIVE";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requireFinding(long runId, String findingId) {
        Map<String, Object> report = runService.getReport(runId);
        List<Map<String, Object>> findings = (List<Map<String, Object>>) report.get("findings");
        return findings.stream()
                .filter(finding -> findingId.equals(finding.get("id")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "finding " + findingId + " not found in run " + runId));
    }

    private long latestEventId() {
        return repo.jdbc().queryForObject(
                "select coalesce(max(id), 0) from event", Long.class);
    }

    private long idOf(Map<String, Object> row) {
        return ((Number) row.get("id")).longValue();
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
