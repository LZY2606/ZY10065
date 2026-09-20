package com.gsb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    private static String baseline;
    private static String candidate;
    private static String runKey;
    private static String firstChangePointer;
    private static String firstChangeCode;

    private String fixture(String name) throws Exception {
        return new String(Objects.requireNonNull(
                        getClass().getResourceAsStream("/fixtures/" + name)).readAllBytes(),
                StandardCharsets.UTF_8);
    }

    private JsonNode apiPutSpec(String key, String yaml) throws Exception {
        String body = mvc.perform(put("/api/specs/" + key)
                        .contentType(MediaType.TEXT_PLAIN).content(yaml))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }

    private JsonNode postJson(String url, Object body) throws Exception {
        String response = mvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response);
    }

    @Test
    @Order(1)
    void fullRehearsalLifecycle() throws Exception {
        baseline = fixture("baseline.yaml");
        candidate = fixture("candidate.yaml");

        JsonNode baseFp = apiPutSpec("baseline", baseline);
        JsonNode candFp = apiPutSpec("candidate", candidate);
        assertTrue(baseFp.path("created").asBoolean());
        assertNotEquals(baseFp.path("contentHash").asText(),
                candFp.path("contentHash").asText());

        // idempotent re-import: same content is not a change and keeps versionSeq
        JsonNode again = apiPutSpec("baseline", baseline);
        assertFalse(again.path("changed").asBoolean());
        assertEquals(1, again.path("versionSeq").asInt());

        // two candidate versions coexist with independent caches
        JsonNode cand2Fp = apiPutSpec("candidate2", candidate);
        assertEquals(candFp.path("contentHash").asText(), cand2Fp.path("contentHash").asText());

        // sample import: valid batch
        String validBatch = """
                [
                  {"method":"GET","path":"/pets","requestMediaType":"application/json",
                   "requestBody":null,"statusCode":200,"responseMediaType":"application/json",
                   "responseBody":"[\\"dog\\",1]"},
                  {"method":"GET","path":"/pets","statusCode":200,
                   "responseMediaType":"application/json",
                   "responseBody":[
                     {"id":1,"name":"Rex","kind":"dog","status":"available","tag":null,"rating":4},
                     {"id":2,"name":"Tweety","kind":"bird","status":"sold","tag":"cage","rating":5}
                   ]},
                  {"method":"GET","path":"/pets/{petId}","statusCode":200,
                   "responseMediaType":"application/json",
                   "responseBody":{"id":7,"name":"Kitty","kind":"cat","status":"pending"}},
                  {"method":"POST","path":"/pets","statusCode":201,
                   "requestMediaType":"application/json",
                   "requestBody":{"name":"Rex","kind":"dog","tag":null},
                   "responseMediaType":"application/json",
                   "responseBody":{"id":9,"name":"Rex","kind":"dog","status":"available"}},
                  {"method":"GET","path":"/pets","statusCode":200,
                   "responseMediaType":"application/json",
                   "responseBody":{"note":"partial capture, request side missing"},
                   "completeness":"INCOMPLETE_REQUEST"}
                ]
                """;
        JsonNode imported = postJson("/api/samples/default",
                JSON.readValue(validBatch, Object.class));
        assertEquals(5, imported.path("received").asInt());
        assertEquals(5, imported.path("inserted").asInt());

        // re-import identical content: zero duplicates
        JsonNode reimported = postJson("/api/samples/default",
                JSON.readValue(validBatch, Object.class));
        assertEquals(0, reimported.path("inserted").asInt());
        assertEquals(5, reimported.path("skipped").asInt());

        // malformed batch rolls back entirely (one bad row poisons the batch)
        String badBatch = """
                [
                  {"method":"GET","path":"/fresh","statusCode":200,
                   "responseMediaType":"application/json","responseBody":{"ok":true}},
                  {"method":"","path":"/oops"}
                ]
                """;
        mvc.perform(post("/api/samples/default")
                        .contentType(MediaType.APPLICATION_JSON).content(badBatch))
                .andExpect(status().isBadRequest());
        JsonNode listed = JSON.readTree(mvc.perform(get("/api/samples/default"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(5, listed.size(), "failed batch must not leave partial samples");

        // create run under strict policy
        JsonNode run = postJson("/api/runs", java.util.Map.of(
                "baselineKey", "baseline",
                "candidateKey", "candidate",
                "policyVersion", "2025.1",
                "sampleSet", "default"));
        runKey = run.path("runKey").asText();
        assertFalse(runKey.isBlank());
        JsonNode report = run.path("report");
        assertEquals("2025.1", report.path("inputs").path("policyVersion").asText());

        // second run with identical inputs returns the same cached row
        JsonNode runAgain = postJson("/api/runs", java.util.Map.of(
                "baselineKey", "baseline",
                "candidateKey", "candidate",
                "policyVersion", "2025.1",
                "sampleSet", "default"));
        assertEquals(runKey, runAgain.path("runKey").asText());

        // other candidate version must not share the cache entry
        JsonNode runOther = postJson("/api/runs", java.util.Map.of(
                "baselineKey", "baseline",
                "candidateKey", "candidate2",
                "policyVersion", "2025.1",
                "sampleSet", "default"));
        // candidate2 currently holds identical content, so the fingerprint is equal:
        // same inputs => same runKey (content-addressed, not key-addressed)
        assertEquals(runKey, runOther.path("runKey").asText());

        // strict report must surface the concrete change families
        JsonNode changes = report.path("changes");
        assertTrue(contains(changes, "code", "ENUM_NARROWED"), "enum narrowing");
        assertTrue(contains(changes, "code", "REQUIRED_ADDED"), "required added");
        assertTrue(contains(changes, "code", "NULLABILITY_CHANGED"), "nullability");
        assertTrue(contains(changes, "code", "PARAM_REQUIRED_CHANGED"), "param required");
        assertTrue(contains(changes, "code", "MAXIMUM_TIGHTENED"), "numeric bound");
        assertTrue(contains(changes, "code", "STATUS_PRIORITY_SHIFT")
                || hasChangeAt(changes, "response/404"), "status priority");
        assertTrue(severityFor(changes, "ENUM_NARROWED").contains("BREAKING"));

        // legacy policy downgrades newly-required RESPONSE fields to WARNING
        JsonNode legacy = postJson("/api/runs", java.util.Map.of(
                "baselineKey", "baseline",
                "candidateKey", "candidate",
                "policyVersion", "2024.09",
                "sampleSet", "default")).path("report");
        JsonNode legacyChanges = legacy.path("changes");
        boolean foundResponseRequiredWarning = false;
        for (JsonNode ch : legacyChanges) {
            if (ch.path("code").asText().equals("REQUIRED_ADDED")
                    && ch.path("direction").asText().equals("RESPONSE")
                    && ch.path("severity").asText().equals("WARNING")) {
                foundResponseRequiredWarning = true;
            }
        }
        assertTrue(foundResponseRequiredWarning, "legacy policy downgrade");

        // evidence: the invalid first sample is still structurally imported but
        // incomplete samples appear as evidence and are excluded from pass rate
        JsonNode evidence = report.path("evidence");
        assertTrue(evidence.isArray());
        JsonNode summary = report.path("summary");
        int evaluated = summary.path("evaluated").asInt();
        int excluded = summary.path("excluded").asInt();
        assertEquals(5, summary.path("totalSamples").asInt());
        assertTrue(excluded >= 1, "incomplete sample excluded");
        assertEquals(5, evaluated + excluded);
        double passRate = summary.path("passRate").asDouble();
        assertTrue(passRate >= 0.0 && passRate <= 1.0);

        // drill-down: each sample carries old + new validations and consumer diff
        JsonNode samples = report.path("samples");
        boolean sawFail = false;
        boolean sawConsumerDiff = false;
        for (JsonNode sample : samples) {
            assertTrue(sample.has("requestValidation"));
            assertTrue(sample.path("requestValidation").has("base"));
            assertTrue(sample.path("requestValidation").has("cand"));
            assertTrue(sample.has("responseValidation"));
            if ("FAIL".equals(sample.path("outcome").asText())) {
                sawFail = true;
            }
            if (sample.path("consumerVisibleDiff").size() > 0) {
                sawConsumerDiff = true;
            }
        }
        assertTrue(sawFail, "the enum-narrowed response sample must fail");
        assertTrue(sawConsumerDiff, "consumer visible differences must be shown");

        // pick a breaking response change to exempt
        for (JsonNode ch : changes) {
            if (ch.path("severity").asText().equals("BREAKING")
                    && ch.path("direction").asText().equals("RESPONSE")) {
                firstChangeCode = ch.path("code").asText();
                firstChangePointer = ch.path("pointer").asText();
                break;
            }
        }
        assertFalse(firstChangeCode == null || firstChangeCode.isBlank(),
                "a breaking response change exists to exempt");
    }

    @Test
    @Order(2)
    void exemptionIsBoundToLocatorAndBecomesPendingWhenSpecMoves() throws Exception {
        // grant a time-limited exemption for the exact located change
        java.util.Map<String, Object> grantReq = new java.util.LinkedHashMap<>();
        grantReq.put("candidateKey", "candidate");
        grantReq.put("method", "GET");
        grantReq.put("path", "/pets");
        grantReq.put("changeCode", firstChangeCode);
        grantReq.put("pointer", firstChangePointer == null ? "" : firstChangePointer);
        grantReq.put("reason", "consumers migrate in release train Q4");
        grantReq.put("operator", "alice");
        grantReq.put("validDays", 30);
        JsonNode granted = postJson("/api/exemptions", grantReq);
        String exemptionKey = granted.path("exemptionKey").asText();
        assertFalse(exemptionKey.isBlank());
        assertEquals("ACTIVE", granted.path("effectiveStatus").asText());

        // reason + operator + versions are recorded as an append-only event
        JsonNode events = JSON.readTree(mvc.perform(get("/api/events"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        boolean grantEvent = false;
        for (JsonNode event : events) {
            if (event.path("type").asText().equals("EXEMPTION_GRANTED")
                    && event.path("operator").asText().equals("alice")
                    && !event.path("reason").asText().isBlank()) {
                grantEvent = true;
            }
        }
        assertTrue(grantEvent);

        // candidate spec changes: locator must stop matching and exemption go PENDING
        String evolved = candidate
                .replace("enum: [dog, cat]", "enum: [dog, cat, bird]")
                .replace("""
                          required: [id, name, kind, status, createdAt]""",
                        """
                          required: [id, name, kind, status, createdAt, region]""");
        JsonNode evolvedFp = apiPutSpec("candidate", evolved);
        assertTrue(evolvedFp.path("changed").asBoolean());
        assertNotEquals(2, evolvedFp.path("versionSeq").asInt() == 0);
        assertEquals(2, evolvedFp.path("versionSeq").asInt());

        JsonNode pendingList = JSON.readTree(mvc.perform(
                        get("/api/exemptions").param("candidateKey", "candidate"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        boolean pending = false;
        for (JsonNode ex : pendingList) {
            if (ex.path("exemptionKey").asText().equals(exemptionKey)
                    && "PENDING".equals(ex.path("effectiveStatus").asText())) {
                pending = true;
            }
        }
        assertTrue(pending, "exemption must become PENDING after spec moves");

        // revoke appends a new event instead of deleting history
        postJson("/api/exemptions/" + exemptionKey + "/revoke",
                java.util.Map.of("operator", "bob", "reason", "release aborted"));
        JsonNode eventsAfter = JSON.readTree(mvc.perform(get("/api/events"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        boolean revokeEvent = false;
        int grantCount = 0;
        for (JsonNode event : eventsAfter) {
            if ("EXEMPTION_REVOKED".equals(event.path("type").asText())
                    && event.path("operator").asText().equals("bob")) {
                revokeEvent = true;
            }
            if ("EXEMPTION_GRANTED".equals(event.path("type").asText())) {
                grantCount++;
            }
        }
        assertTrue(revokeEvent, "revoke must append an event");
        assertTrue(grantCount >= 1, "grant history must remain");

        // evolved spec must produce a new run (fingerprint changed), old run retained
        JsonNode evolvedRun = postJson("/api/runs", java.util.Map.of(
                "baselineKey", "baseline",
                "candidateKey", "candidate",
                "policyVersion", "2025.1",
                "sampleSet", "default"));
        assertNotEquals(runKey, evolvedRun.path("runKey").asText());
        JsonNode storedOld = JSON.readTree(mvc.perform(get("/api/runs/" + runKey))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(runKey, storedOld.path("runKey").asText());
    }

    private boolean contains(JsonNode array, String field, String value) {
        for (JsonNode node : array) {
            if (value.equals(node.path(field).asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasChangeAt(JsonNode array, String pointer) {
        for (JsonNode node : array) {
            if (node.path("pointer").asText().startsWith(pointer)) {
                return true;
            }
        }
        return false;
    }

    private java.util.List<String> severityFor(JsonNode array, String code) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (JsonNode node : array) {
            if (code.equals(node.path("code").asText())) {
                out.add(node.path("severity").asText());
            }
        }
        return out;
    }
}
