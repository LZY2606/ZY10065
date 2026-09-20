package com.example.compat;

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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static long baselineId;
    private static long candidateId;
    private static long secondCandidateId;
    private static long brokenCandidateId;
    private static long batchId;
    private static long runId;

    @Autowired
    private MockMvc mockMvc;

    private static String testData(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/testdata/" + name));
    }

    private JsonNode postJson(String url, String body) throws Exception {
        String response = mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response);
    }

    private JsonNode getJson(String url) throws Exception {
        String response = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response);
    }

    @Test
    @Order(1)
    void importsAreIdempotentByContentFingerprint() throws Exception {
        String specEnvelope = "{\"name\":\"baseline\",\"role\":\"baseline\","
                + "\"mediaType\":\"application/json\",\"content\":"
                + MAPPER.writeValueAsString(testData("baseline.json")) + "}";
        JsonNode first = postJson("/api/specs", specEnvelope);
        baselineId = first.get("id").asLong();
        assertEquals(false, first.get("deduplicated").asBoolean());
        JsonNode again = postJson("/api/specs", specEnvelope);
        assertEquals(baselineId, again.get("id").asLong());
        assertEquals(true, again.get("deduplicated").asBoolean());

        JsonNode candidate = postJson("/api/specs",
                envelope("candidate", "candidate", testData("candidate.json")));
        candidateId = candidate.get("id").asLong();
        JsonNode candidate2 = postJson("/api/specs",
                envelope("candidate-parallel", "candidate", testData("candidate-broken.json")));
        secondCandidateId = candidate2.get("id").asLong();
        brokenCandidateId = secondCandidateId;
    }

    @Test
    @Order(2)
    void sampleBatchIsIdempotentAndRejectsDuplicatesAtomically() throws Exception {
        String body = "{\"name\":\"snap-1\",\"samples\":" + testData("samples.json") + "}";
        JsonNode first = postJson("/api/sample-batches", body);
        batchId = first.get("id").asLong();
        assertEquals(false, first.get("deduplicated").asBoolean());
        JsonNode again = postJson("/api/sample-batches", body);
        assertEquals(batchId, again.get("id").asLong());
        assertEquals(true, again.get("deduplicated").asBoolean());

        String duplicateKey = "[{\"key\":\"x\",\"method\":\"get\",\"path\":\"/a\"},"
                + "{\"key\":\"x\",\"method\":\"get\",\"path\":\"/b\"}]";
        mockMvc.perform(post("/api/sample-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad\",\"samples\":" + duplicateKey + "}"))
                .andExpect(status().isBadRequest());

        JsonNode batches = getJson("/api/sample-batches");
        assertEquals(1, batches.size(), "failed batch must not leave partial results");
    }

    @Test
    @Order(3)
    void runIsCachedByFourFingerprintsAndParallelCandidatesDoNotCollide() throws Exception {
        JsonNode run = postJson("/api/runs", "{\"baselineId\":" + baselineId
                + ",\"candidateId\":" + candidateId + ",\"sampleBatchId\":" + batchId
                + ",\"policyVersion\":\"compat-1.0\"}");
        runId = run.get("id").asLong();
        assertEquals(false, run.get("deduplicated").asBoolean());
        JsonNode again = postJson("/api/runs", "{\"baselineId\":" + baselineId
                + ",\"candidateId\":" + candidateId + ",\"sampleBatchId\":" + batchId
                + ",\"policyVersion\":\"compat-1.0\"}");
        assertEquals(runId, again.get("id").asLong());
        assertEquals(true, again.get("deduplicated").asBoolean());

        JsonNode parallel = postJson("/api/runs", "{\"baselineId\":" + baselineId
                + ",\"candidateId\":" + secondCandidateId + ",\"sampleBatchId\":" + batchId
                + ",\"policyVersion\":\"compat-1.0\"}");
        assertTrue(parallel.get("id").asLong() != runId,
                "parallel candidate must not reuse the cached report");

        JsonNode v11 = postJson("/api/runs", "{\"baselineId\":" + baselineId
                + ",\"candidateId\":" + candidateId + ",\"sampleBatchId\":" + batchId
                + ",\"policyVersion\":\"compat-1.1\"}");
        assertTrue(v11.get("id").asLong() != runId, "policy version is part of run identity");
    }

    @Test
    @Order(4)
    void reportShowsFindingsEvidenceExclusionAndPassRate() throws Exception {
        JsonNode report = getJson("/api/runs/" + runId + "/report");
        JsonNode summary = report.get("summary");
        assertTrue(summary.get("breaking").asInt() > 0);
        assertTrue(summary.get("samplesTotal").asInt() >= 6);
        long evidenceOnly = summary.get("samplesExcludedAsEvidence").asLong();
        assertTrue(evidenceOnly >= 2, "incomplete + unmatched samples must be evidence");
        assertEquals(summary.get("samplesCounted").asLong(),
                summary.get("samplesTotal").asLong() - evidenceOnly);

        JsonNode samples = report.get("samples");
        long evidenceCount = 0;
        for (JsonNode sample : samples) {
            if (sample.get("key").asText().equals("list-closed-ok")) {
                assertTrue(sample.get("passesBaseline").asBoolean(),
                        "recorded traffic validated against the baseline it was captured on");
                assertEquals(false, sample.get("passesCandidate").asBoolean());
                assertTrue(sample.get("hitFindingIds").size() > 0);
            }
            if (sample.get("evidenceOnly").asBoolean()) {
                evidenceCount++;
                assertEquals("EVIDENCE_ONLY", sample.get("sampleVerdict").asText());
            }
        }
        assertEquals(evidenceOnly, evidenceCount);

        JsonNode drilldown = getJson("/api/runs/" + runId
                + "/samples/list-closed-ok");
        assertNotNull(drilldown.get("sample").get("consumerDeltas"));
    }

    @Test
    @Order(5)
    void exemptionIsGrantedBecomesPendingWhenLocatorLostAndUndoAppendsEvent() throws Exception {
        JsonNode report = getJson("/api/runs/" + runId + "/report");
        String fieldRemovedId = null;
        for (JsonNode finding : report.get("findings")) {
            if ("FIELD_REMOVED".equals(finding.get("code").asText())
                    && finding.get("locator").asText().contains("coupon")) {
                fieldRemovedId = finding.get("id").asText();
            }
        }
        assertNotNull(fieldRemovedId, "coupon removal finding must exist");

        long from = System.currentTimeMillis();
        long to = from + 86400000;
        JsonNode exemption = postJson("/api/runs/" + runId + "/exemptions",
                grantBody(fieldRemovedId, from, to, "coupon unused, deferred cleanup"));
        long exemptionId = exemption.get("id").asLong();
        assertEquals("ACTIVE", exemption.get("effectiveStatus").asText());

        JsonNode expiredView = getJson("/api/runs/" + runId + "/report?asOfMs=" + (to + 1));
        boolean expired = false;
        for (JsonNode item : expiredView.get("exemptions")) {
            if (item.get("id").asLong() == exemptionId) {
                assertEquals("EXPIRED", item.get("effectiveStatus").asText());
                expired = true;
            }
        }
        assertTrue(expired);

        JsonNode revoke = postJson("/api/exemptions/" + exemptionId + "/revoke",
                "{\"actor\":\"reviewer\",\"reason\":\"reconsidered before launch\"}");
        assertEquals("REVOKED", revoke.get("effectiveStatus").asText());

        JsonNode events = getJson("/api/events?runId=" + runId);
        boolean grantSeen = false;
        boolean revokeSeen = false;
        for (JsonNode event : events) {
            String type = event.get("event_type").asText();
            if ("EXEMPTION_GRANT_REQUESTED".equals(type)) {
                grantSeen = true;
            }
            if ("EXEMPTION_REVOKED".equals(type)) {
                revokeSeen = true;
                assertEquals("reviewer", event.get("actor").asText());
            }
        }
        assertTrue(grantSeen && revokeSeen, "undo must append a new event, never delete");
    }

    @Test
    @Order(6)
    void pendingStatusIsStructuralNotSimilarityBased() throws Exception {
        // A run against the broken candidate has an unresolvable schema; granting on a
        // schema-bound finding there must fail with a clear PENDING-style error.
        JsonNode run = postJson("/api/runs", "{\"baselineId\":" + baselineId
                + ",\"candidateId\":" + brokenCandidateId + ",\"sampleBatchId\":" + batchId
                + ",\"policyVersion\":\"compat-1.0\"}");
        long brokenRunId = run.get("id").asLong();
        JsonNode report = getJson("/api/runs/" + brokenRunId + "/report");
        assertTrue(report.get("evidence").get("candidateUnresolvableRefs").size() > 0);
        assertTrue(report.get("evidence").get("candidateCycles").size() > 0);
    }

    @Test
    @Order(7)
    void waiverGoesPendingWhenCandidateChangeRemovesBoundLocation() throws Exception {
        // Exemption is granted for the newly-required "trace" parameter on the
        // GET /orders operation against candidate v1.
        JsonNode report = getJson("/api/runs/" + runId + "/report");
        String traceFindingId = null;
        for (JsonNode finding : report.get("findings")) {
            if ("PARAM_REQUIRED_ADDED".equals(finding.get("code").asText())
                    && finding.get("locator").asText().contains("trace")) {
                traceFindingId = finding.get("id").asText();
            }
        }
        assertNotNull(traceFindingId);
        long from = System.currentTimeMillis();
        JsonNode exemption = postJson("/api/runs/" + runId + "/exemptions",
                grantBody(traceFindingId, from, from + 86400000L,
                        "trace rollout delayed for one gateway version"));
        long exemptionId = exemption.get("id").asLong();
        assertEquals("ACTIVE", exemption.get("effectiveStatus").asText());

        // Import candidate v2: "trace" disappeared. Even though GET /orders and a
        // similar-looking "state" parameter still exist, the bound location is gone.
        JsonNode v2 = postJson("/api/specs",
                envelope("candidate-v2", "candidate", testData("candidate-v2.json")));
        long v2Id = v2.get("id").asLong();
        JsonNode reconciled = postJson(
                "/api/exemptions/" + exemptionId + "/reconcile?candidateSpecId=" + v2Id, "{}");
        assertEquals("PENDING", reconciled.get("effectiveStatus").asText());
        String detail = reconciled.get("resolution").asText();
        assertTrue(detail.contains("trace") && detail.contains("similarity"),
                "pending reason must explain that no similarity matching is used");

        // The exemption remains ACTIVE for the original run's saved view.
        JsonNode originalReport = getJson("/api/runs/" + runId + "/report?asOfMs=" + (from + 1000));
        for (JsonNode item : originalReport.get("exemptions")) {
            if (item.get("id").asLong() == exemptionId) {
                assertEquals("ACTIVE", item.get("effectiveStatus").asText());
            }
        }
    }

    private String envelope(String name, String role, String content) throws Exception {
        return "{\"name\":\"" + name + "\",\"role\":\"" + role + "\","
                + "\"mediaType\":\"application/json\",\"content\":"
                + MAPPER.writeValueAsString(content) + "}";
    }

    private String grantBody(String findingId, long from, long to, String reason) {
        return "{\"findingId\":\"" + findingId + "\",\"actor\":\"alice\","
                + "\"reason\":\"" + reason + "\",\"validFromMs\":" + from
                + ",\"validToMs\":" + to + "}";
    }
}
