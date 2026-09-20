package com.gsb;

import com.gsb.engine.MediaBody;
import com.gsb.engine.OpenApiDoc;
import com.gsb.engine.OperationInfo;
import com.gsb.engine.ParseIssue;
import com.gsb.engine.ParsedSpec;
import com.gsb.engine.SchemaNode;
import com.gsb.engine.SpecParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecParserTest {

    private String fixture(String name) throws IOException {
        return new String(Objects.requireNonNull(
                        getClass().getResourceAsStream("/fixtures/" + name)).readAllBytes(),
                StandardCharsets.UTF_8);
    }

    @Test
    void resolvesRefsAndInheritsPathLevelParameters() throws Exception {
        ParsedSpec parsed = SpecParser.parse(fixture("baseline.yaml"));
        OpenApiDoc doc = parsed.doc();
        OperationInfo post = doc.operations().get("POST /pets");
        assertNotNull(post);
        assertTrue(post.params().stream().anyMatch(p -> p.name().equals("X-Trace-Id")));
        MediaBody body = post.requestBody();
        assertTrue(body.required());
        SchemaNode newPet = body.jsonSchema();
        assertEquals(SchemaNode.OBJECT, newPet.kind());
        assertTrue(newPet.required().contains("kind"));
        assertEquals(SchemaNode.INTEGER,
                post.responses().get("201").jsonSchema().properties().get("id").kind());
    }

    @Test
    void fingerprintIsStableForIdenticalBytes() throws Exception {
        String text = fixture("baseline.yaml");
        assertEquals(SpecParser.fingerprint(text).sha256(),
                SpecParser.fingerprint(text).sha256());
    }

    @Test
    void unresolvedExternalRefBecomesEvidenceNotException() throws Exception {
        String yaml = """
                openapi: 3.0.3
                info: {title: t, version: "1"}
                paths:
                  /x:
                    get:
                      responses:
                        "200":
                          description: ok
                          content:
                            application/json:
                              schema:
                                $ref: "other.yaml#/components/schemas/X"
                """;
        ParsedSpec parsed = SpecParser.parse(yaml);
        assertTrue(parsed.issues().stream()
                .anyMatch(i -> i.kind().equals(ParseIssue.UNRESOLVED_REF)));
        SchemaNode node = parsed.doc().operations().get("GET /x")
                .responses().get("200").jsonSchema();
        assertEquals(SchemaNode.REF_UNRESOLVED, node.kind());
    }

    @Test
    void missingLocalRefBecomesUnresolvedNode() throws Exception {
        String yaml = """
                openapi: 3.0.3
                info: {title: t, version: "1"}
                paths:
                  /x:
                    get:
                      responses:
                        "200":
                          description: ok
                          content:
                            application/json:
                              schema:
                                $ref: "#/components/schemas/Missing"
                """;
        ParsedSpec parsed = SpecParser.parse(yaml);
        assertTrue(parsed.issues().stream()
                .anyMatch(i -> i.kind().equals(ParseIssue.UNRESOLVED_REF)));
    }

    @Test
    void cyclicRefsAreMarkedAndDoNotStackOverflow() throws Exception {
        String yaml = """
                openapi: 3.0.3
                info: {title: t, version: "1"}
                paths:
                  /x:
                    get:
                      responses:
                        "200":
                          description: ok
                          content:
                            application/json:
                              schema:
                                $ref: "#/components/schemas/A"
                components:
                  schemas:
                    A:
                      type: object
                      properties:
                        child:
                          $ref: "#/components/schemas/B"
                    B:
                      type: object
                      properties:
                        parent:
                          $ref: "#/components/schemas/A"
                """;
        ParsedSpec parsed = SpecParser.parse(yaml);
        assertTrue(parsed.issues().stream().anyMatch(i -> i.kind().equals(ParseIssue.CYCLE)));
        SchemaNode a = parsed.doc().operations().get("GET /x")
                .responses().get("200").jsonSchema();
        SchemaNode cycle = a.properties().get("child").properties().get("parent");
        assertEquals(SchemaNode.CYCLE, cycle.kind());
        assertFalse(parsed.issues().isEmpty());
    }
}
