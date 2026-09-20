package com.gsb;

import com.gsb.engine.Change;
import com.gsb.engine.Differ;
import com.gsb.engine.MediaBody;
import com.gsb.engine.OperationInfo;
import com.gsb.engine.ParamInfo;
import com.gsb.engine.SchemaNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DifferTest {

    private SchemaNode obj(boolean requiredTag, Map<String, SchemaNode> props, List<String> required) {
        return new SchemaNode(SchemaNode.OBJECT, props, required, null, null, null,
                List.of(), null, null, null, null, null, null, null, List.of(), null);
    }

    private SchemaNode stringEnum(List<Object> values) {
        return new SchemaNode(SchemaNode.STRING, Map.of(), List.of(), null, null, null,
                values, null, null, null, null, null, null, null, List.of(), null);
    }

    @Test
    void enumNarrowingOnResponseIsBreaking() {
        SchemaNode base = obj(false, Map.of("kind", stringEnum(List.of("dog", "cat", "bird"))),
                List.of("kind"));
        SchemaNode cand = obj(false, Map.of("kind", stringEnum(List.of("dog", "cat"))),
                List.of("kind"));
        OperationInfo b = op(base);
        OperationInfo c = op(cand);
        List<Change> changes = Differ.diffOperation(b, c);
        assertTrue(changes.stream().anyMatch(ch ->
                ch.code().equals("ENUM_NARROWED")
                        && Change.RESPONSE.equals(ch.direction())
                        && Change.BREAKING.equals(ch.severity())));
    }

    @Test
    void requiredFieldAddedToResponseBodyIsBreakingByDefault() {
        SchemaNode base = obj(false, Map.of(), List.of());
        SchemaNode cand = obj(false,
                Map.of("createdAt", new SchemaNode(SchemaNode.STRING, Map.of(), List.of(),
                        null, null, null, List.of(), null, null, null, null, null,
                        null, null, List.of(), null)),
                List.of("createdAt"));
        List<Change> changes = Differ.diffOperation(op(base), op(cand));
        assertTrue(changes.stream().anyMatch(ch ->
                ch.code().equals("REQUIRED_ADDED") && Change.BREAKING.equals(ch.severity())));
    }

    @Test
    void responseFieldRemovalIsBreakingForConsumers() {
        SchemaNode base = obj(false,
                Map.of("tag", new SchemaNode(SchemaNode.STRING, Map.of(), List.of(),
                        null, null, null, List.of(), null, null, null, null, null,
                        null, null, List.of(), null)),
                List.of());
        SchemaNode cand = obj(false, Map.of(), List.of());
        List<Change> changes = Differ.diffOperation(op(base), op(cand));
        assertTrue(changes.stream().anyMatch(ch ->
                ch.code().equals("FIELD_REMOVED") && Change.BREAKING.equals(ch.severity())
                        && Change.RESPONSE.equals(ch.direction())));
    }

    @Test
    void responseFieldBecomingNullableIsBreakingForConsumers() {
        SchemaNode nonNull = new SchemaNode(SchemaNode.STRING, Map.of(), List.of(), null,
                null, null, List.of(), null, null, null, null, null, null, null,
                List.of(), null);
        SchemaNode nullable = new SchemaNode(SchemaNode.STRING, Map.of(), List.of(), null,
                Boolean.TRUE, null, List.of(), null, null, null, null, null, null, null,
                List.of(), null);
        SchemaNode base = obj(false, Map.of("tag", nonNull), List.of());
        SchemaNode cand = obj(false, Map.of("tag", nullable), List.of());
        List<Change> changes = Differ.diffOperation(op(base), op(cand));
        assertTrue(changes.stream().anyMatch(ch ->
                ch.code().equals("NULLABILITY_CHANGED")
                        && Change.BREAKING.equals(ch.severity())
                        && Change.RESPONSE.equals(ch.direction())),
                changes.toString());
    }

    @Test
    void requestFieldBecomingNonNullIsBreakingForCallers() {
        SchemaNode nullable = new SchemaNode(SchemaNode.STRING, Map.of(), List.of(), null,
                Boolean.TRUE, null, List.of(), null, null, null, null, null, null, null,
                List.of(), null);
        SchemaNode nonNull = new SchemaNode(SchemaNode.STRING, Map.of(), List.of(), null,
                null, null, List.of(), null, null, null, null, null, null, null,
                List.of(), null);
        OperationInfo b = new OperationInfo("POST", "/pets", "x", List.of(),
                new MediaBody(Map.of("application/json",
                        obj(false, Map.of("tag", nullable), List.of())), true), Map.of());
        OperationInfo c = new OperationInfo("POST", "/pets", "x", List.of(),
                new MediaBody(Map.of("application/json",
                        obj(false, Map.of("tag", nonNull), List.of())), true), Map.of());
        List<Change> changes = Differ.diffOperation(b, c);
        assertTrue(changes.stream().anyMatch(ch ->
                ch.code().equals("NULLABILITY_CHANGED")
                        && Change.BREAKING.equals(ch.severity())
                        && Change.REQUEST.equals(ch.direction())),
                changes.toString());
    }

    @Test
    void requiredRequestParameterAddedIsBreaking() {
        ParamInfo optional = new ParamInfo("X-Trace-Id", "header", false, "",
                new SchemaNode(SchemaNode.STRING, Map.of(), List.of(), null, null, null,
                        List.of(), null, null, null, null, null, null, null, List.of(), null));
        ParamInfo required = new ParamInfo("X-Trace-Id", "header", true, "",
                new SchemaNode(SchemaNode.STRING, Map.of(), List.of(), null, null, null,
                        List.of(), null, null, null, null, null, null, null, List.of(), null));
        OperationInfo b = new OperationInfo("POST", "/pets", "x", List.of(optional),
                MediaBody.ABSENT, Map.of());
        OperationInfo c = new OperationInfo("POST", "/pets", "x", List.of(required),
                MediaBody.ABSENT, Map.of());
        List<Change> changes = Differ.diffOperation(b, c);
        assertTrue(changes.stream().anyMatch(ch ->
                ch.code().equals("PARAM_REQUIRED_CHANGED")
                        && Change.BREAKING.equals(ch.severity())));
    }

    @Test
    void mediaTypeRemovedFromResponseIsBreaking() {
        MediaBody base = new MediaBody(Map.of(
                "application/json", emptyObject(),
                "application/xml", emptyObject()), false);
        MediaBody cand = new MediaBody(Map.of("application/json", emptyObject()), false);
        OperationInfo b = new OperationInfo("GET", "/pets", "x", List.of(),
                MediaBody.ABSENT, Map.of("200", base));
        OperationInfo c = new OperationInfo("GET", "/pets", "x", List.of(),
                MediaBody.ABSENT, Map.of("200", cand));
        List<Change> changes = Differ.diffOperation(b, c);
        assertTrue(changes.stream().anyMatch(ch ->
                ch.code().equals("MEDIA_REMOVED") && Change.BREAKING.equals(ch.severity())));
    }

    @Test
    void statusPriorityExactToWildcardIsShift() {
        OperationInfo b = new OperationInfo("POST", "/pets", "x", List.of(),
                MediaBody.ABSENT, Map.of(
                        "201", body("created"),
                        "default", body("other-error")));
        OperationInfo c = new OperationInfo("POST", "/pets", "x", List.of(),
                MediaBody.ABSENT, Map.of(
                        "201", body("created"),
                        "4XX", body("client-error"),
                        "default", body("other-error")));
        // 404 resolves default -> default: no shift. Build a case where 404 had a
        // dedicated 404 response in baseline but only 4XX/default in candidate.
        OperationInfo b2 = new OperationInfo("POST", "/pets", "x", List.of(),
                MediaBody.ABSENT, Map.of(
                        "201", body("created"),
                        "404", body("not-found"),
                        "default", body("other-error")));
        List<Change> changes = Differ.diffOperation(b2, c);
        assertTrue(changes.stream().anyMatch(ch ->
                ch.code().equals("STATUS_PRIORITY_SHIFT")
                        && ch.pointer().equals("response/404")),
                changes.toString());
        assertFalse(Differ.resolveKey(b.responses(), "404")
                .equals(Differ.resolveKey(c.responses(), "201")));
        assertEquals("4XX", Differ.resolveKey(c.responses(), "404"));
        assertEquals("404", Differ.resolveKey(b2.responses(), "404"));
    }

    private MediaBody body(String label) {
        Map<String, SchemaNode> props = Map.of("message_" + label,
                new SchemaNode(SchemaNode.STRING, Map.of(), List.of(), null, null, null,
                        List.of(), null, null, null, null, null, null, null, List.of(), null));
        return new MediaBody(Map.of("application/json", obj(false, props, List.of())), false);
    }

    private SchemaNode emptyObject() {
        return new SchemaNode(SchemaNode.EMPTY, Map.of(), List.of(), null, null, null,
                List.of(), null, null, null, null, null, null, null, List.of(), null);
    }

    private OperationInfo op(SchemaNode responseSchema) {
        return new OperationInfo("GET", "/pets", "x", List.of(), MediaBody.ABSENT,
                Map.of("200", new MediaBody(Map.of("application/json", responseSchema), false)));
    }
}
