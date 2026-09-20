package com.example.compat.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Assembles a run report purely from saved inputs and a policy version:
 * structural findings, independent evidence, and per-sample old/new validation.
 */
public final class ReportEngine {

    private final SpecModel baseline;
    private final SpecModel candidate;
    private final List<SampleModel> samples;
    private final CompatibilityPolicy policy;
    private final SampleValidator validator = new SampleValidator();

    public ReportEngine(SpecModel baseline, SpecModel candidate, List<SampleModel> samples,
                        CompatibilityPolicy policy) {
        this.baseline = baseline;
        this.candidate = candidate;
        this.samples = samples;
        this.policy = policy;
    }

    public Map<String, Object> assemble() {
        List<Finding> findings = new Differ(policy).diff(baseline, candidate);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("policyVersion", policy.version());
        report.put("baselineFingerprint", fingerprint(baseline));
        report.put("candidateFingerprint", fingerprint(candidate));

        Map<String, Object> evidence = collectEvidence();
        report.put("evidence", evidence);
        report.put("findings", findings.stream().map(this::findingMap).toList());

        List<Map<String, Object>> sampleResults = new ArrayList<>();
        for (SampleModel sample : samples) {
            sampleResults.add(replay(sample, findings));
        }
        report.put("samples", sampleResults);
        report.put("summary", summarize(findings, evidence, sampleResults));
        return report;
    }

    private Map<String, Object> collectEvidence() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("baselineUnresolvableRefs",
                new ArrayList<>(baseline.getResolver().unresolvableRefs()));
        evidence.put("candidateUnresolvableRefs",
                new ArrayList<>(candidate.getResolver().unresolvableRefs()));
        evidence.put("baselineCycles", new ArrayList<>(baseline.getResolver().cycleRefs()));
        evidence.put("candidateCycles", new ArrayList<>(candidate.getResolver().cycleRefs()));
        int refCount = baseline.getResolver().unresolvableRefs().size()
                + candidate.getResolver().unresolvableRefs().size();
        int cycleCount = baseline.getResolver().cycleRefs().size()
                + candidate.getResolver().cycleRefs().size();
        evidence.put("structuralProblemCount", refCount + cycleCount);
        return evidence;
    }

    private Map<String, Object> replay(SampleModel sample, List<Finding> findings) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", sample.key());
        result.put("method", sample.method());
        result.put("path", sample.path());
        result.put("statusCode", sample.statusCode());

        ValidationResult oldRequest = validator.validateRequest(baseline, sample);
        ValidationResult newRequest = validator.validateRequest(candidate, sample);
        ValidationResult oldResponse = validator.validateResponse(baseline, sample);
        ValidationResult newResponse = validator.validateResponse(candidate, sample);

        result.put("oldRequest", validationMap(oldRequest));
        result.put("newRequest", validationMap(newRequest));
        result.put("oldResponse", validationMap(oldResponse));
        result.put("newResponse", validationMap(newResponse));

        boolean structural = oldRequest.hasStructuralEvidence()
                || newRequest.hasStructuralEvidence()
                || oldResponse.hasStructuralEvidence()
                || newResponse.hasStructuralEvidence();
        boolean incomplete = !oldRequest.isComplete() || !newRequest.isComplete()
                || !oldResponse.isComplete() || !newResponse.isComplete();
        boolean matchedOld = baseline.getOperations()
                .containsKey(SpecModel.opKey(sample.method(), sample.path()));
        boolean matchedNew = candidate.getOperations()
                .containsKey(SpecModel.opKey(sample.method(), sample.path()));

        List<String> evidenceKinds = new ArrayList<>();
        if (structural) {
            evidenceKinds.add(StructuralEvidence.UNRESOLVABLE_REF + "/"
                    + StructuralEvidence.CYCLE);
        }
        if (incomplete) {
            evidenceKinds.add(StructuralEvidence.INCOMPLETE_SAMPLE);
        }
        if (!matchedOld || !matchedNew) {
            evidenceKinds.add(StructuralEvidence.UNMATCHED_SAMPLE);
        }
        result.put("evidenceKinds", evidenceKinds.stream().distinct().sorted().toList());
        boolean evidenceOnly = !evidenceKinds.isEmpty();
        result.put("evidenceOnly", evidenceOnly);

        List<Finding> hits = new ArrayList<>();
        if (!evidenceOnly) {
            for (Finding finding : findings) {
                if (hits(finding, sample, oldRequest, newRequest,
                        oldResponse, newResponse)) {
                    hits.add(finding);
                }
            }
        }
        result.put("hitFindingIds", hits.stream().map(Finding::getId).sorted().toList());

        List<Map<String, Object>> consumerDeltas = new ArrayList<>();
        if (!evidenceOnly) {
            for (Finding finding : hits) {
                consumerDeltas.add(consumerDelta(finding, oldRequest, newRequest,
                        oldResponse, newResponse));
            }
        }
        result.put("consumerDeltas", consumerDeltas);

        boolean oldPass = oldRequest.isValid() && oldResponse.isValid();
        boolean newPass = newRequest.isValid() && newResponse.isValid();
        result.put("passesBaseline", oldPass);
        result.put("passesCandidate", newPass);
        if (!evidenceOnly) {
            result.put("sampleVerdict", newPass ? "PASS" : "FAIL");
        } else {
            result.put("sampleVerdict", "EVIDENCE_ONLY");
        }
        return result;
    }

    private boolean hits(Finding finding, SampleModel sample,
                         ValidationResult oldRequest, ValidationResult newRequest,
                         ValidationResult oldResponse, ValidationResult newResponse) {
        if (!finding.getMethod().equalsIgnoreCase(sample.method())
                || !finding.getPath().equals(sample.path())) {
            return false;
        }
        if (Severity.EVIDENCE.name().equals(finding.getSeverity())) {
            return false;
        }
        String code = finding.getCode();
        Side side = Side.valueOf(finding.getSide());
        if (ChangeCodes.OP_REMOVED.equals(code)) {
            return true;
        }
        String expectedType = expectedTypeFor(finding);
        if (side == Side.REQUEST) {
            return newRequest.getIssues().stream().anyMatch(issue ->
                    relates(code, issue) && typeMatches(expectedType, issue));
        }
        return oldResponse.isValid() && newResponse.getIssues().stream().anyMatch(issue ->
                relates(code, issue) && typeMatches(expectedType, issue));
    }

    private String expectedTypeFor(Finding finding) {
        if (ChangeCodes.TYPE_CHANGED.equals(finding.getCode())
                && finding.getAfter().get("type") != null) {
        return String.valueOf(finding.getAfter().get("type"));
        }
        return null;
    }

    private boolean typeMatches(String expectedType, String issue) {
        if (expectedType == null) {
            return true;
        }
        return issue.contains("expected " + expectedType);
    }

    /** Maps a change code to what a concrete payload looks like after the change. */
    private boolean relates(String code, String issue) {
        return switch (code) {
            case ChangeCodes.REQUIRED_FIELD_ADDED, ChangeCodes.FIELD_BECAME_REQUIRED,
                 ChangeCodes.PARAM_BECAME_REQUIRED, ChangeCodes.PARAM_REQUIRED_ADDED,
                 ChangeCodes.BODY_BECAME_REQUIRED ->
                    issue.contains("required field missing") || issue.contains("required");
            case ChangeCodes.TYPE_CHANGED -> issue.contains("expected ");
            case ChangeCodes.ENUM_NARROWED, ChangeCodes.ENUM_CHANGED ->
                    issue.contains("not in enum");
            case ChangeCodes.NULLABILITY_TIGHTENED ->
                    issue.contains("non-nullable");
            case ChangeCodes.MINIMUM_RAISED, ChangeCodes.MAXIMUM_LOWERED ->
                    issue.contains("minimum") || issue.contains("maximum");
            case ChangeCodes.MIN_LENGTH_RAISED, ChangeCodes.MAX_LENGTH_LOWERED ->
                    issue.contains("minLength") || issue.contains("maxLength");
            case ChangeCodes.ADDITIONAL_PROPERTIES_CLOSED ->
                    issue.contains("additionalProperties=false");
            case ChangeCodes.STATUS_REMOVED, ChangeCodes.STATUS_ADDED ->
                    issue.contains("status");
            default -> false;
        };
    }

    private Map<String, Object> consumerDelta(Finding finding,
                                              ValidationResult oldRequest,
                                              ValidationResult newRequest,
                                              ValidationResult oldResponse,
                                              ValidationResult newResponse) {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("findingId", finding.getId());
        delta.put("code", finding.getCode());
        delta.put("severity", finding.getSeverity());
        delta.put("side", finding.getSide());
        delta.put("observedOnSample", true);
        delta.put("detail", finding.getSummary());
        if (Side.REQUEST.name().equals(finding.getSide())) {
            delta.put("oldValid", oldRequest.isValid());
            delta.put("newValid", newRequest.isValid());
            delta.put("newIssues", newRequest.getIssues());
        } else {
            delta.put("oldValid", oldResponse.isValid());
            delta.put("newValid", newResponse.isValid());
            delta.put("newIssues", newResponse.getIssues());
        }
        return delta;
    }

    private Map<String, Object> validationMap(ValidationResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("complete", result.isComplete());
        map.put("valid", result.isValid());
        map.put("issues", result.getIssues());
        map.put("evidence", result.getEvidences().stream()
                .map(evidence -> Map.of("kind", evidence.kind(), "detail", evidence.detail()))
                .toList());
        return map;
    }

    private Map<String, Object> findingMap(Finding finding) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", finding.getId());
        map.put("code", finding.getCode());
        map.put("severity", finding.getSeverity());
        map.put("side", finding.getSide());
        map.put("method", finding.getMethod());
        map.put("path", finding.getPath());
        map.put("anchor", finding.getAnchor());
        map.put("subject", finding.getSubject());
        map.put("locator", finding.getLocator());
        map.put("summary", finding.getSummary());
        map.put("before", finding.getBefore());
        map.put("after", finding.getAfter());
        return map;
    }

    private Map<String, Object> summarize(List<Finding> findings,
                                          Map<String, Object> evidence,
                                          List<Map<String, Object>> sampleResults) {
        Map<String, Object> summary = new LinkedHashMap<>();
        long breaking = findings.stream()
                .filter(finding -> Severity.BREAKING.name().equals(finding.getSeverity()))
                .count();
        long nonBreaking = findings.stream()
                .filter(finding -> Severity.NON_BREAKING.name().equals(finding.getSeverity()))
                .count();
        long info = findings.stream()
                .filter(finding -> Severity.INFO.name().equals(finding.getSeverity()))
                .count();
        summary.put("totalFindings", findings.size());
        summary.put("breaking", breaking);
        summary.put("nonBreaking", nonBreaking);
        summary.put("info", info);
        summary.put("structuralProblemCount", evidence.get("structuralProblemCount"));

        long total = sampleResults.size();
        long evidenceOnly = sampleResults.stream()
                .filter(result -> Boolean.TRUE.equals(result.get("evidenceOnly"))).count();
        long countable = total - evidenceOnly;
        long passed = sampleResults.stream()
                .filter(result -> "PASS".equals(result.get("sampleVerdict"))).count();
        long failed = sampleResults.stream()
                .filter(result -> "FAIL".equals(result.get("sampleVerdict"))).count();
        summary.put("samplesTotal", total);
        summary.put("samplesExcludedAsEvidence", evidenceOnly);
        summary.put("samplesCounted", countable);
        summary.put("samplesPassed", passed);
        summary.put("samplesFailed", failed);
        double rate = countable == 0 ? 0.0
                : Math.round(passed * 10000.0 / countable) / 100.0;
        summary.put("passRatePercent", rate);
        summary.put("verdict", breaking == 0 && failed == 0
                ? "COMPATIBLE" : "REVIEW_REQUIRED");
        return summary;
    }

    private String fingerprint(SpecModel spec) {
        return Canonicalizer.fingerprint(spec.getRoot());
    }
}
