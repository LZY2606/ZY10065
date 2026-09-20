package com.example.compat;

import com.example.compat.engine.Canonicalizer;
import com.example.compat.engine.ChangeCodes;
import com.example.compat.engine.Finding;
import com.example.compat.engine.PolicyRegistry;
import com.example.compat.engine.SpecModel;
import com.example.compat.engine.SpecParser;
import com.example.compat.engine.StructuralEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineTest {

    private final SpecParser parser = new SpecParser();

    private SpecModel load(String resource) throws IOException {
        String content = Files.readString(Path.of("src/test/resources/testdata/" + resource));
        JsonNode root = Canonicalizer.parse("application/json", content);
        return parser.parse(root);
    }

    @Test
    void resolvesComponentsAndInheritsPathParameters() throws Exception {
        SpecModel baseline = load("baseline.json");
        assertNotNull(baseline.getOperations().get("GET /orders"));
        var listOrders = baseline.getOperations().get("GET /orders");
        assertTrue(listOrders.getParameters().stream()
                .anyMatch(parameter -> "X-Tenant".equals(parameter.getName())));
        assertTrue(listOrders.getParameters().stream()
                .anyMatch(parameter -> "limit".equals(parameter.getName())));
        var orderSchema = listOrders.getResponses().get("200").getMediaTypes()
                .get("application/json").getItems();
        assertTrue(orderSchema.getProperties().containsKey("amount"));
    }

    @Test
    void detectsRequiredNullableEnumBoundsMediaTypesAndStatusPriorities() throws Exception {
        SpecModel baseline = load("baseline.json");
        SpecModel candidate = load("candidate.json");
        var differ = new com.example.compat.engine.Differ(PolicyRegistry.require("compat-1.0"));
        List<Finding> findings = differ.diff(baseline, candidate);
        Map<String, List<Finding>> byCode = findings.stream()
                .collect(Collectors.groupingBy(Finding::getCode));

        assertTrue(byCode.containsKey(ChangeCodes.PARAM_REQUIRED_ADDED));
        assertTrue(byCode.containsKey(ChangeCodes.ENUM_NARROWED));
        assertTrue(byCode.containsKey(ChangeCodes.MAXIMUM_LOWERED));
        assertTrue(byCode.containsKey(ChangeCodes.DEFAULT_CHANGED));
        assertTrue(byCode.containsKey(ChangeCodes.REQUIRED_FIELD_ADDED));
        assertTrue(byCode.containsKey(ChangeCodes.FIELD_REMOVED));
        assertTrue(byCode.containsKey(ChangeCodes.ENUM_WIDENED));
        assertTrue(byCode.containsKey(ChangeCodes.TYPE_CHANGED));
        assertTrue(byCode.containsKey(ChangeCodes.STATUS_REMOVED));
        assertTrue(byCode.containsKey(ChangeCodes.DEFAULT_STATUS_REMOVED));
        assertTrue(byCode.containsKey(ChangeCodes.OP_ADDED));

        // response-side enum widening (customer.tier) is breaking; request-side
        // narrowing (state query) is breaking too under 1.0.
        Finding tier = byCode.get(ChangeCodes.ENUM_WIDENED).stream()
                .filter(finding -> finding.getLocator().contains("tier")).findFirst().orElseThrow();
        assertEquals("BREAKING", tier.getSeverity());

        // stable ids and deterministic order across recomputation
        var secondRun = new com.example.compat.engine.Differ(
                PolicyRegistry.require("compat-1.0")).diff(baseline, candidate);
        assertEquals(findings.stream().map(Finding::getId).toList(),
                secondRun.stream().map(Finding::getId).toList());
        assertEquals(findings.stream().map(Finding::getLocator).toList(),
                secondRun.stream().map(Finding::getLocator).toList());
    }

    @Test
    void policyVersionChangesSeverityButNotFindings() throws Exception {
        SpecModel baseline = load("baseline.json");
        SpecModel candidate = load("candidate.json");
        List<Finding> v10 = new com.example.compat.engine.Differ(
                PolicyRegistry.require("compat-1.0")).diff(baseline, candidate);
        List<Finding> v11 = new com.example.compat.engine.Differ(
                PolicyRegistry.require("compat-1.1")).diff(baseline, candidate);
        assertEquals(v10.size(), v11.size());
        Function<List<Finding>, Map<String, String>> severities = (findings) ->
                findings.stream().collect(Collectors.toMap(Finding::getCode,
                        Finding::getSeverity, (left, right) -> left));
        assertEquals("NON_BREAKING", severities.apply(v10).get(ChangeCodes.DEFAULT_CHANGED));
        assertEquals("INFO", severities.apply(v11).get(ChangeCodes.DEFAULT_CHANGED));
    }

    @Test
    void unresolvableRefsAndCyclesAreIndependentEvidence() throws Exception {
        SpecModel broken = load("candidate-broken.json");
        assertFalse(broken.getResolver().unresolvableRefs().isEmpty());
        assertFalse(broken.getResolver().cycleRefs().isEmpty());
        assertTrue(broken.getResolver().unresolvableRefs().stream()
                .anyMatch(ref -> ref.contains("External")));
        assertTrue(broken.getResolver().cycleRefs().stream()
                .anyMatch(ref -> ref.endsWith("LoopA")));
        assertEquals("UNRESOLVABLE_REF", StructuralEvidence.UNRESOLVABLE_REF);
    }

    @Test
    void validatorFlagsEnumBoundsRequiredAndIncompleteSamples() throws Exception {
        SpecModel baseline = load("baseline.json");
        SpecModel candidate = load("candidate.json");
        var samples = TestSamples.load();
        var validator = new com.example.compat.engine.SampleValidator();

        var closedOld = validator.validateResponse(baseline, samples.get(0));
        var closedNew = validator.validateResponse(candidate, samples.get(0));
        assertTrue(closedOld.isValid(), closedOld.getIssues()::toString);
        assertFalse(closedNew.isValid());
        assertTrue(closedNew.getIssues().stream().anyMatch(issue -> issue.contains("enum")));

        var overLimitOld = validator.validateResponse(baseline, samples.get(2));
        var overLimitNew = validator.validateResponse(candidate, samples.get(2));
        assertTrue(overLimitOld.isValid());
        assertFalse(overLimitNew.isValid());
        assertTrue(overLimitNew.getIssues().stream().anyMatch(issue -> issue.contains("maximum")));

        var incomplete = validator.validateResponse(candidate, samples.get(4));
        assertFalse(incomplete.isComplete());
        assertTrue(incomplete.getIssues().stream()
                .anyMatch(issue -> issue.contains(StructuralEvidence.INCOMPLETE_SAMPLE)));

        var unmatched = validator.validateResponse(candidate, samples.get(5));
        assertFalse(unmatched.isValid());
    }
}
