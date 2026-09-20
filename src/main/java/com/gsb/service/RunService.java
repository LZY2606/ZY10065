package com.gsb.service;

import com.gsb.engine.Canonical;
import com.gsb.engine.Change;
import com.gsb.engine.MediaBody;
import com.gsb.engine.OpenApiDoc;
import com.gsb.engine.OperationInfo;
import com.gsb.engine.ParseIssue;
import com.gsb.engine.ParsedSpec;
import com.gsb.engine.SchemaNode;
import com.gsb.engine.SchemaValidator;
import com.gsb.engine.SpecParser;
import com.gsb.engine.Violation;
import com.gsb.model.Exemption;
import com.gsb.model.ExemptionRepository;
import com.gsb.model.RunReport;
import com.gsb.model.RunReportRepository;
import com.gsb.model.SampleRecord;
import com.gsb.model.SampleRecordRepository;
import com.gsb.model.SpecDocument;
import com.gsb.model.SpecDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builds a deterministic compatibility report from the two saved specs,
 * the selected policy version and the immutable snapshot of a sample set.
 * Everything in reportJson is reproducible from those inputs only.
 */
@Service
public class RunService {

    private final SpecDocumentRepository specRepo;
    private final SampleRecordRepository sampleRepo;
    private final RunReportRepository runRepo;
    private final ExemptionRepository exemptionRepo;

    public RunService(SpecDocumentRepository specRepo,
                      SampleRecordRepository sampleRepo,
                      RunReportRepository runRepo,
                      ExemptionRepository exemptionRepo) {
        this.specRepo = specRepo;
        this.sampleRepo = sampleRepo;
        this.runRepo = runRepo;
        this.exemptionRepo = exemptionRepo;
    }

    public record RunRequest(String baselineKey, String candidateKey,
                             String policyVersion, String sampleSet) {
    }

    /** A change annotated with the exact operation it belongs to. */
    record LocatedChange(String method, String path, Change change) {
        String locator() {
            return locatorKey(method, path, change.code(),
                    change.pointer() == null ? "" : change.pointer());
        }
    }

    @Transactional
    public RunReport createOrFetch(RunRequest req) {
        String setName = req.sampleSet() == null || req.sampleSet().isBlank()
                ? "default" : req.sampleSet().trim();
        Policy policy = Policy.resolve(req.policyVersion());

        SpecDocument base = specRepo.findBySpecKey(req.baselineKey())
                .orElseThrow(() -> new NotFoundException("baseline spec not found: " + req.baselineKey()));
        SpecDocument cand = specRepo.findBySpecKey(req.candidateKey())
                .orElseThrow(() -> new NotFoundException("candidate spec not found: " + req.candidateKey()));

        List<SampleRecord> samples = sampleRepo.findBySampleSetOrderByIdAsc(setName);
        List<String> dedupKeys = samples.stream().map(SampleRecord::getDedupKey)
                .sorted().toList();
        String sampleSetHash = Canonical.sha256(dedupKeys);
        // Content-addressed: two candidate versions with different fingerprints
        // always get independent cached rows; identical content never duplicates.
        String runKey = Canonical.sha256(List.of(
                base.getContentHash(),
                cand.getContentHash(),
                policy.version(),
                sampleSetHash));

        return runRepo.findByRunKey(runKey).orElseGet(() -> {
            String reportJson = buildReport(base, cand, policy, setName, samples,
                    sampleSetHash, runKey);
            RunReport report = new RunReport();
            report.setRunKey(runKey);
            report.setBaselineHash(base.getContentHash());
            report.setCandidateKey(req.candidateKey().trim());
            report.setCandidateHash(cand.getContentHash());
            report.setPolicyVersion(policy.version());
            report.setSampleSet(setName);
            report.setSampleSetHash(sampleSetHash);
            report.setStatus("DONE");
            report.setReportJson(reportJson);
            RunReport saved = runRepo.save(report);
            saved.setReportJson(decorate(saved));
            return saved;
        });
    }

    @Transactional(readOnly = true)
    public RunReport require(String runKey) {
        RunReport report = runRepo.findByRunKey(runKey)
                .orElseThrow(() -> new NotFoundException("run not found: " + runKey));
        report.setReportJson(decorate(report));
        return report;
    }

    /**
     * Applies the currently saved, effective exemptions on top of the frozen
     * structural report. The raw analysis never changes; this overlay is itself
     * reproducible from the exemption/event tables (saved human decisions).
     */
    @Transactional(readOnly = true)
    public String decorate(RunReport stored) {
        Map<String, Object> root;
        try {
            root = Json.MAPPER.readValue(stored.getReportJson(), Map.class);
        } catch (Exception e) {
            return stored.getReportJson();
        }
        Instant now = Instant.now();
        List<Exemption> exemptions =
                exemptionRepo.findByCandidateKeyOrderByIdAsc(stored.getCandidateKey());
        Set<String> active = new TreeSet<>();
        Map<String, String> locatorToKey = new TreeMap<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) root.get("changes");
        Set<String> currentLocators = new TreeSet<>();
        for (Map<String, Object> change : changes) {
            currentLocators.add(locatorOf(change));
        }
        for (Exemption ex : exemptions) {
            String locator = locatorKey(ex.getMethod(), ex.getPath(),
                    ex.getChangeCode(), ex.getPointer() == null ? "" : ex.getPointer());
            boolean live = !Exemption.REVOKED.equals(ex.getStatus())
                    && (ex.getValidUntil() == null || !ex.getValidUntil().isBefore(now))
                    && ex.getBoundVersion().equals(stored.getCandidateHash())
                    && currentLocators.contains(locator);
            if (live) {
                active.add(locator);
                locatorToKey.putIfAbsent(locator, ex.getExemptionKey());
            }
        }

        int coveredSamples = 0;
        int effectivePass = 0;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> samples = (List<Map<String, Object>>) root.get("samples");
        for (Map<String, Object> sample : samples) {
            String method = s(sample.get("method"));
            String path = s(sample.get("path"));
            boolean hasUncovered = false;
            boolean hasCovered = false;
            for (Map<String, Object> change : changes) {
                if (!method.equals(s(change.get("method"))) || !path.equals(s(change.get("path")))) {
                    continue;
                }
                if (!"BREAKING".equals(s(change.get("severity")))) {
                    continue;
                }
                // Only response-direction breaking changes determine the stored
                // response sample consumer outcome; request-side breaks are
                // assessed through the sample's request validation separately.
                if (!"RESPONSE".equals(s(change.get("direction")))
                        && !"OPERATION".equals(s(change.get("direction")))) {
                    continue;
                }
                String locator = locatorOf(change);
                String key = locatorToKey.get(locator);
                if (active.contains(locator)) {
                    hasCovered = true;
                    change.put("exemptedBy", key);
                } else {
                    hasUncovered = true;
                }
            }
            if (Boolean.TRUE.equals(sample.get("included"))) {
                boolean regressed = hasNewViolation(sample, "requestValidation")
                        || hasNewViolation(sample, "responseValidation");
                boolean rawPass = "PASS".equals(sample.get("outcome"));
                sample.put("validationRegressed", regressed);
                sample.put("structureExempted", hasCovered && !hasUncovered);
                if (!regressed && hasCovered && !hasUncovered) {
                    sample.put("exempted", true);
                    sample.put("outcomeEffective", "PASS");
                    coveredSamples++;
                    if (!rawPass) {
                        effectivePass++;
                    }
                } else if (rawPass && !hasUncovered) {
                    sample.put("outcomeEffective", "PASS");
                } else {
                    sample.put("outcomeEffective", sample.get("outcome"));
                }
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) root.get("summary");
        int evaluated = ((Number) summary.getOrDefault("evaluated", 0)).intValue();
        int rawPass = ((Number) summary.getOrDefault("pass", 0)).intValue();
        int effectivePassTotal = rawPass + effectivePass;
        summary.put("exempted", coveredSamples);
        summary.put("passEffective", effectivePassTotal);
        summary.put("passRateEffective", evaluated == 0 ? 0.0
                : round4((double) effectivePassTotal / evaluated));
        return Json.write(root);
    }

    private static String locatorOf(Map<String, Object> change) {
        Object locatorObj = change.get("locator");
        if (locatorObj instanceof Map<?, ?> locator) {
            return locatorKey(s(locator.get("method")), s(locator.get("path")),
                    s(locator.get("code")), s(locator.get("pointer")));
        }
        return locatorKey(s(change.get("method")), s(change.get("path")),
                s(change.get("code")), s(change.get("pointer")));
    }

    @SuppressWarnings("unchecked")
    private static boolean hasNewViolation(Map<String, Object> sample, String section) {
        Object sectionObj = sample.get(section);
        if (!(sectionObj instanceof Map<?, ?> sectionMap)) {
            return false;
        }
        Set<String> base = new TreeSet<>();
        Object baseList = sectionMap.get("base");
        if (baseList instanceof List<?> baseItems) {
            for (Object v : baseItems) {
                if (v instanceof Map<?, ?> mv) {
                    base.add(s(mv.get("pointer")) + "\u0001" + s(mv.get("rule")));
                }
            }
        }
        Object candList = sectionMap.get("cand");
        if (candList instanceof List<?> candItems) {
            for (Object v : candItems) {
                if (v instanceof Map<?, ?> mv && !base.contains(
                        s(mv.get("pointer")) + "\u0001" + s(mv.get("rule")))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Transactional(readOnly = true)
    public List<RunReport> list() {
        return runRepo.findAllByOrderByRunKeyAsc();
    }

    private String buildReport(SpecDocument baseDoc, SpecDocument candDoc, Policy policy,
                               String setName, List<SampleRecord> samples,
                               String sampleSetHash, String runKey) {
        ParsedSpec baseParsed = parse(baseDoc);
        ParsedSpec candParsed = parse(candDoc);

        List<Map<String, Object>> evidence = new ArrayList<>();
        addSpecIssues(evidence, baseParsed.issues(), "baseline");
        addSpecIssues(evidence, candParsed.issues(), "candidate");

        List<LocatedChange> located = diffAll(baseParsed.doc(), candParsed.doc(), policy);
        Set<String> currentLocators = new TreeSet<>();
        for (LocatedChange lc : located) {
            currentLocators.add(lc.locator());
        }

        Instant now = Instant.now();
        List<Exemption> exemptions =
                exemptionRepo.findByCandidateKeyOrderByIdAsc(candDoc.getSpecKey());
        List<EffectiveExemption> effective = new ArrayList<>();
        for (Exemption ex : exemptions) {
            String locator = locatorKey(ex.getMethod(), ex.getPath(),
                    ex.getChangeCode(), ex.getPointer());
            effective.add(evaluate(ex, locator, currentLocators, candDoc.getContentHash(), now));
        }

        List<Map<String, Object>> changeEntries = new ArrayList<>();
        for (LocatedChange lc : located) {
            String exemptionKey = null;
            for (EffectiveExemption ee : effective) {
                if (ee.active() && ee.locator().equals(lc.locator())) {
                    exemptionKey = ee.exemption().getExemptionKey();
                    break;
                }
            }
            changeEntries.add(changeToMap(lc, exemptionKey));
        }

        List<Map<String, Object>> sampleEntries = new ArrayList<>();
        int pass = 0;
        int failCount = 0;
        int evaluated = 0;
        int excluded = 0;
        int exemptedCount = 0;
        for (SampleRecord sample : samples) {
            SampleAssessment assessment = assess(sample, baseParsed.doc(), candParsed.doc(),
                    evidence, located, effective);
            sampleEntries.add(assessment.toMap());
            if (assessment.included()) {
                evaluated++;
                if (assessment.outcome().equals("PASS")) {
                    pass++;
                } else {
                    failCount++;
                }
                if (assessment.exempted()) {
                    exemptedCount++;
                }
            } else {
                excluded++;
            }
        }

        int breaking = 0;
        int warning = 0;
        int compatible = 0;
        for (LocatedChange lc : located) {
            String sev = lc.change().severity();
            if ("BREAKING".equals(sev)) {
                breaking++;
            } else if ("WARNING".equals(sev)) {
                warning++;
            } else {
                compatible++;
            }
        }

        Map<String, Object> summary = Json.map();
        summary.put("breaking", breaking);
        summary.put("compatible", compatible);
        summary.put("evaluated", evaluated);
        summary.put("excluded", excluded);
        summary.put("exempted", exemptedCount);
        summary.put("pass", pass);
        summary.put("passRate", evaluated == 0 ? 0.0 : round4((double) pass / evaluated));
        summary.put("totalSamples", samples.size());
        summary.put("warning", warning);

        Map<String, Object> inputs = Json.map();
        inputs.put("baselineHash", baseDoc.getContentHash());
        inputs.put("candidateHash", candDoc.getContentHash());
        inputs.put("candidateKey", candDoc.getSpecKey());
        inputs.put("policyVersion", policy.version());
        inputs.put("runKey", runKey);
        inputs.put("sampleSet", setName);
        inputs.put("sampleSetHash", sampleSetHash);

        evidence.sort((a, b) -> Json.write(a).compareTo(Json.write(b)));
        List<Map<String, Object>> dedupedEvidence = new ArrayList<>();
        String last = null;
        for (Map<String, Object> row : evidence) {
            String sig = Json.write(row);
            if (!sig.equals(last)) {
                dedupedEvidence.add(row);
                last = sig;
            }
        }

        Map<String, Object> root = Json.map();
        root.put("changes", changeEntries);
        root.put("evidence", dedupedEvidence);
        root.put("inputs", inputs);
        root.put("samples", sampleEntries);
        root.put("summary", summary);
        return Json.write(root);
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static ParsedSpec parse(SpecDocument doc) {
        try {
            return SpecParser.parse(doc.getRawText());
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "stored spec cannot be parsed for key " + doc.getSpecKey()
                            + ": " + e.getMessage(), e);
        }
    }

    private static void addSpecIssues(List<Map<String, Object>> evidence,
                                      List<ParseIssue> issues, String side) {
        if (issues == null) {
            return;
        }
        List<ParseIssue> copy = new ArrayList<>(issues);
        copy.sort((a, b) -> {
            int c = s(a.kind()).compareTo(s(b.kind()));
            if (c != 0) {
                return c;
            }
            c = s(a.pointer()).compareTo(s(b.pointer()));
            return c != 0 ? c : s(a.detail()).compareTo(s(b.detail()));
        });
        for (ParseIssue issue : copy) {
            Map<String, Object> ev = Json.map();
            ev.put("detail", s(issue.detail()));
            ev.put("kind", issue.kind() == null ? "BAD_SPEC" : issue.kind());
            ev.put("pointer", s(issue.pointer()));
            ev.put("sampleId", "");
            ev.put("scope", side);
            evidence.add(ev);
        }
    }

    private static String s(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static List<LocatedChange> diffAll(OpenApiDoc base, OpenApiDoc cand, Policy policy) {
        List<LocatedChange> out = new ArrayList<>();
        Set<String> keys = new TreeSet<>();
        keys.addAll(base.operations().keySet());
        keys.addAll(cand.operations().keySet());
        for (String key : keys) {
            OperationInfo b = base.operations().get(key);
            OperationInfo c = cand.operations().get(key);
            if (b == null) {
                out.add(new LocatedChange(c.method(), c.path(),
                        new Change("OPERATION_ADDED", "", "COMPATIBLE", "OPERATION",
                                "operation added: " + key, null, key)));
            } else if (c == null) {
                out.add(new LocatedChange(b.method(), b.path(),
                        new Change("OPERATION_REMOVED", "", "BREAKING", "OPERATION",
                                "operation removed: " + key, key, null)));
            } else {
                for (Change ch : com.gsb.engine.Differ.diffOperation(b, c)) {
                    String severity = policy.classify(ch.code(), ch.direction(), ch.severity());
                    Change effective = severity.equals(ch.severity())
                            ? ch
                            : new Change(ch.code(), ch.pointer(), severity, ch.direction(),
                                    ch.detail(), ch.oldValue(), ch.newValue());
                    out.add(new LocatedChange(b.method(), b.path(), effective));
                }
            }
        }
        out.sort((a, b2) -> {
            int cmp = a.locator().compareTo(b2.locator());
            if (cmp != 0) {
                return cmp;
            }
            cmp = s(a.change().direction()).compareTo(s(b2.change().direction()));
            return cmp != 0 ? cmp : s(a.change().detail()).compareTo(s(b2.change().detail()));
        });
        return out;
    }

    private static String chosenKey(Map<String, MediaBody> responses, String status) {
        if (responses == null) {
            return "";
        }
        if (responses.containsKey(status)) {
            return status;
        }
        String wildcard = status.substring(0, 1) + "XX";
        if (responses.containsKey(wildcard)) {
            return wildcard;
        }
        return responses.containsKey("default") ? "default" : "";
    }

    private record EffectiveExemption(Exemption exemption, String locator, boolean active) {
    }

    /**
     * Effective status is derived from the exact granted locator fields; there
     * is no fuzzy or string-similarity matching. A locator that no longer points
     * at a change, a changed bound spec hash, or an expired window disables it.
     */
    private static EffectiveExemption evaluate(Exemption ex, String locator,
                                               Set<String> currentLocators,
                                               String currentHash, Instant now) {
        if (Exemption.REVOKED.equals(ex.getStatus())) {
            return new EffectiveExemption(ex, locator, false);
        }
        if (ex.getValidUntil() != null && ex.getValidUntil().isBefore(now)) {
            return new EffectiveExemption(ex, locator, false);
        }
        if (!ex.getBoundVersion().equals(currentHash)) {
            return new EffectiveExemption(ex, locator, false);
        }
        if (!currentLocators.contains(locator)) {
            return new EffectiveExemption(ex, locator, false);
        }
        return new EffectiveExemption(ex, locator, true);
    }

    static String locatorKey(String method, String path, String code, String pointer) {
        return method + "\u0001" + path + "\u0001" + code + "\u0001"
                + (pointer == null ? "" : pointer);
    }

    private static Map<String, Object> changeToMap(LocatedChange lc, String exemptionKey) {
        Change ch = lc.change();
        Map<String, Object> locator = Json.map();
        locator.put("code", s(ch.code()));
        locator.put("method", s(lc.method()));
        locator.put("path", s(lc.path()));
        locator.put("pointer", s(ch.pointer()));

        Map<String, Object> map = Json.map();
        map.put("code", s(ch.code()));
        map.put("detail", s(ch.detail()));
        map.put("direction", s(ch.direction()));
        map.put("exemptedBy", exemptionKey == null ? "" : exemptionKey);
        map.put("locator", locator);
        map.put("method", s(lc.method()));
        map.put("path", s(lc.path()));
        map.put("pointer", s(ch.pointer()));
        map.put("newValue", ch.newValue() == null ? "" : ch.newValue());
        map.put("oldValue", ch.oldValue() == null ? "" : ch.oldValue());
        map.put("severity", s(ch.severity()));
        return map;
    }

    private static final class SampleAssessment {
        private final SampleRecord sample;
        private final boolean included;
        private final String excludeReason;
        private final List<Violation> baseReq;
        private final List<Violation> candReq;
        private final List<Violation> baseResp;
        private final List<Violation> candResp;
        private final boolean exempted;
        private final String outcome;
        private final List<String> consumerVisibleDiff;

        private SampleAssessment(SampleRecord sample, boolean included, String excludeReason,
                                 List<Violation> baseReq, List<Violation> candReq,
                                 List<Violation> baseResp, List<Violation> candResp,
                                 boolean exempted, String outcome,
                                 List<String> consumerVisibleDiff) {
            this.sample = sample;
            this.included = included;
            this.excludeReason = excludeReason;
            this.baseReq = baseReq;
            this.candReq = candReq;
            this.baseResp = baseResp;
            this.candResp = candResp;
            this.exempted = exempted;
            this.outcome = outcome;
            this.consumerVisibleDiff = consumerVisibleDiff;
        }

        boolean included() {
            return included;
        }

        String outcome() {
            return outcome;
        }

        boolean exempted() {
            return exempted;
        }

        Map<String, Object> toMap() {
            Map<String, Object> rv = Json.map();
            rv.put("consumerVisibleDiff", consumerVisibleDiff);
            rv.put("excludeReason", excludeReason);
            rv.put("exempted", exempted);
            rv.put("included", included);
            rv.put("method", s(sample.getMethod()));
            rv.put("outcome", outcome);
            rv.put("path", s(sample.getPath()));
            Map<String, Object> requestValidation = Json.map();
            requestValidation.put("base", violations(baseReq));
            requestValidation.put("cand", violations(candReq));
            Map<String, Object> responseValidation = Json.map();
            responseValidation.put("base", violations(baseResp));
            responseValidation.put("cand", violations(candResp));
            rv.put("requestValidation", requestValidation);
            rv.put("responseValidation", responseValidation);
            rv.put("sampleId", sample.getDedupKey());
            rv.put("statusCode", sample.getStatusCode() == null ? "" : sample.getStatusCode());
            return rv;
        }

        private static List<Map<String, Object>> violations(List<Violation> violations) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Violation v : violations) {
                Map<String, Object> map = Json.map();
                map.put("detail", s(v.detail()));
                map.put("pointer", s(v.pointer()));
                map.put("rule", s(v.rule()));
                out.add(map);
            }
            return out;
        }
    }

    private SampleAssessment assess(SampleRecord sample, OpenApiDoc base, OpenApiDoc cand,
                                    List<Map<String, Object>> evidence,
                                    List<LocatedChange> changes,
                                    List<EffectiveExemption> exemptions) {
        String id = sample.getDedupKey();

        if (!SampleRecord.COMPLETE.equals(sample.getCompleteness())) {
            addSampleEvidence(evidence, "INCOMPLETE_SAMPLE",
                    "#/" + sample.getMethod() + " " + sample.getPath(),
                    "sample is " + sample.getCompleteness(), id);
            return excluded(sample, "INCOMPLETE_SAMPLE:" + sample.getCompleteness());
        }

        OperationInfo baseOp = findOperation(base, sample.getMethod(), sample.getPath());
        OperationInfo candOp = findOperation(cand, sample.getMethod(), sample.getPath());
        if (baseOp == null && candOp == null) {
            return excluded(sample, "OPERATION_NOT_IN_SPEC");
        }

        SchemaNode baseReqSchema = requestSchema(baseOp, sample.getRequestMediaType());
        SchemaNode candReqSchema = requestSchema(candOp, sample.getRequestMediaType());
        String statusKey = sample.getStatusCode() == null
                ? "" : String.valueOf(sample.getStatusCode());
        SchemaNode baseRespSchema = responseSchema(baseOp, statusKey,
                sample.getResponseMediaType());
        SchemaNode candRespSchema = responseSchema(candOp, statusKey,
                sample.getResponseMediaType());

        Object requestData = parseJsonOrNull(sample.getRequestBody());
        Object responseData = parseJsonOrNull(sample.getResponseBody());

        // Only markers the concrete payload actually reaches are evidence; an
        // unresolved/cycle branch in an unused property must not exclude it.
        List<TroubleHit> hits = new ArrayList<>();
        collectTrouble(hits, baseReqSchema, requestData);
        collectTrouble(hits, candReqSchema, requestData);
        collectTrouble(hits, baseRespSchema, responseData);
        collectTrouble(hits, candRespSchema, responseData);
        if (!hits.isEmpty()) {
            hits.sort((a, b) -> a.kind().compareTo(b.kind()));
            String trouble = hits.get(0).kind();
            addSampleEvidence(evidence, trouble,
                    "#" + sample.getMethod() + " " + sample.getPath(),
                    "validation reached a " + trouble + " schema", id);
            return excluded(sample, trouble);
        }

        List<Violation> baseReq = validate(baseReqSchema, requestData);
        List<Violation> candReq = validate(candReqSchema, requestData);
        List<Violation> baseResp = validate(baseRespSchema, responseData);
        List<Violation> candResp = validate(candRespSchema, responseData);

        List<LocatedChange> relevant = changes.stream()
                .filter(lc -> lc.method().equals(sample.getMethod())
                        && lc.path().equals(sample.getPath()))
                .sorted((a, b) -> a.locator().compareTo(b.locator()))
                .toList();

        List<String> consumerDiff = new ArrayList<>();
        boolean hasUncoveredBreaking = false;
        boolean hasCoveredBreaking = false;
        for (LocatedChange lc : relevant) {
            String sev = lc.change().severity();
            String key = lc.locator();
            String covering = null;
            for (EffectiveExemption ee : exemptions) {
                if (ee.active() && ee.locator().equals(key)) {
                    covering = ee.exemption().getExemptionKey();
                    break;
                }
            }
            boolean breaking = "BREAKING".equals(sev);
            if (breaking && covering == null) {
                hasUncoveredBreaking = true;
            }
            if (breaking && covering != null) {
                hasCoveredBreaking = true;
            }
            if (visibleToConsumer(lc.change().direction())) {
                consumerDiff.add(describe(lc, covering));
            }
        }

        boolean requestBroken = regressed(baseReq, candReq);
        boolean responseBroken = regressed(baseResp, candResp);
        boolean fail = requestBroken || responseBroken || hasUncoveredBreaking;
        boolean exempted = hasCoveredBreaking && !fail;
        if (hasCoveredBreaking && !hasUncoveredBreaking
                && !requestBroken && !responseBroken) {
            exempted = true;
        }
        String outcome = fail ? "FAIL" : "PASS";
        return new SampleAssessment(sample, true, "", baseReq, candReq,
                baseResp, candResp, exempted, outcome, consumerDiff);
    }

    private static SampleAssessment excluded(SampleRecord sample, String reason) {
        return new SampleAssessment(sample, false, reason,
                List.of(), List.of(), List.of(), List.of(),
                false, "EXCLUDED", List.of());
    }

    private static boolean visibleToConsumer(String direction) {
        return "RESPONSE".equals(direction) || "OPERATION".equals(direction);
    }

    private static String describe(LocatedChange lc, String covering) {
        Change ch = lc.change();
        String text = "[" + s(ch.severity()) + "] " + s(ch.direction()) + " "
                + s(ch.pointer()) + " " + s(ch.code()) + ": " + s(ch.detail());
        return covering == null ? text : text + " (exempted " + covering + ")";
    }

    private static void addSampleEvidence(List<Map<String, Object>> evidence, String kind,
                                          String pointer, String detail, String sampleId) {
        Map<String, Object> ev = Json.map();
        ev.put("detail", detail);
        ev.put("kind", kind);
        ev.put("pointer", pointer);
        ev.put("sampleId", sampleId);
        ev.put("scope", "sample");
        evidence.add(ev);
        evidence.sort((a, b) -> {
            int c = String.valueOf(a.get("kind")).compareTo(String.valueOf(b.get("kind")));
            if (c != 0) {
                return c;
            }
            c = String.valueOf(a.get("sampleId")).compareTo(String.valueOf(b.get("sampleId")));
            return c != 0 ? c : String.valueOf(a.get("pointer"))
                    .compareTo(String.valueOf(b.get("pointer")));
        });
    }

    private static List<Violation> validate(SchemaNode schema, Object data) {
        if (schema == null || data == null) {
            return List.of();
        }
        List<Violation> out = new ArrayList<>(SchemaValidator.validate(schema, data));
        out.sort((a, b) -> {
            int c = s(a.pointer()).compareTo(s(b.pointer()));
            if (c != 0) {
                return c;
            }
            return s(a.rule()).compareTo(s(b.rule()));
        });
        return out;
    }

    private static Object parseJsonOrNull(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Json.MAPPER.readValue(text, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Exact "METHOD path" lookup first, then a single unambiguous template match
     * (path segments compared pairwise, {param} segments match one segment).
     */
    private static OperationInfo findOperation(OpenApiDoc doc, String method, String path) {
        if (doc == null) {
            return null;
        }
        String exact = method + " " + path;
        OperationInfo hit = doc.operations().get(exact);
        if (hit != null) {
            return hit;
        }
        List<OperationInfo> candidates = new ArrayList<>();
        for (OperationInfo op : doc.operations().values()) {
            if (op.method().equals(method) && pathTemplateMatches(op.path(), path)) {
                candidates.add(op);
            }
        }
        candidates.sort((a, b) -> a.path().compareTo(b.path()));
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static boolean pathTemplateMatches(String template, String concrete) {
        String[] t = template.split("/");
        String[] c = concrete.split("/");
        if (t.length != c.length) {
            return false;
        }
        for (int i = 0; i < t.length; i++) {
            if (t[i].startsWith("{") && t[i].endsWith("}")) {
                continue;
            }
            if (!t[i].equals(c[i])) {
                return false;
            }
        }
        return true;
    }

    private static SchemaNode requestSchema(OperationInfo op, String mediaType) {
        if (op == null || op.requestBody() == null) {
            return null;
        }
        return pickMedia(op.requestBody(), mediaType);
    }

    private static SchemaNode responseSchema(OperationInfo op, String status, String mediaType) {
        if (op == null || status.isEmpty()) {
            return null;
        }
        MediaBody body = op.responses() == null ? null
                : op.responses().get(chosenKey(op.responses(), status));
        return pickMedia(body, mediaType);
    }

    private static SchemaNode pickMedia(MediaBody body, String mediaType) {
        if (body == null) {
            return null;
        }
        if (mediaType != null && body.content().containsKey(mediaType)) {
            return body.content().get(mediaType);
        }
        TreeSet<String> sorted = new TreeSet<>(body.content().keySet());
        if (sorted.isEmpty()) {
            return null;
        }
        for (String candidate : sorted) {
            if ("application/json".equals(candidate) || candidate.endsWith("+json")) {
                return body.content().get(candidate);
            }
        }
        return body.content().get(sorted.first());
    }

    private record TroubleHit(String kind, String pointer) {
    }

    private static void collectTrouble(List<TroubleHit> hits, SchemaNode node, Object data) {
        if (node == null || data == null) {
            return;
        }
        if (!SchemaValidator.touchedTrouble(node, data)) {
            return;
        }
        List<Violation> violations = SchemaValidator.validate(node, data).stream()
                .filter(v -> "UNRESOLVED_REF".equals(v.rule()) || "CYCLE".equals(v.rule()))
                .toList();
        for (Violation v : violations) {
            hits.add(new TroubleHit(v.rule(), v.pointer()));
        }
        if (violations.isEmpty()) {
            hits.add(new TroubleHit(SchemaNode.REF_UNRESOLVED.equals(node.kind())
                    ? "UNRESOLVED_REF" : "CYCLE", "$"));
        }
    }

    /**
     * A sample regresses when the candidate introduces violations that did not
     * exist against the baseline (a larger violation set), not when it was
     * already invalid.
     */
    private static boolean regressed(List<Violation> baseViolations,
                                     List<Violation> candViolations) {
        if (candViolations.isEmpty()) {
            return false;
        }
        Set<String> baseKeys = new TreeSet<>();
        for (Violation v : baseViolations) {
            baseKeys.add(v.pointer() + "" + v.rule() + "" + v.detail());
        }
        for (Violation v : candViolations) {
            if (!baseKeys.contains(v.pointer() + "" + v.rule() + "" + v.detail())) {
                return true;
            }
        }
        return false;
    }
}
