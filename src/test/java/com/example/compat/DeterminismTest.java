package com.example.compat;

import com.example.compat.engine.Canonicalizer;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DeterminismTest {

    @Test
    void keyOrderAndFormatDoNotChangeFingerprint() throws Exception {
        String jsonA = "{\"b\":2,\"a\":{\"y\":1,\"x\":[3,1,2]}}";
        String jsonB = "{\n  \"a\": {\"x\": [3, 1, 2], \"y\": 1},\n  \"b\": 2\n}";
        JsonNode nodeA = Canonicalizer.parse("application/json", jsonA);
        JsonNode nodeB = Canonicalizer.parse("application/json", jsonB);
        assertEquals(Canonicalizer.fingerprint(nodeA), Canonicalizer.fingerprint(nodeB));
    }

    @Test
    void yamlAndJsonOfSameDocumentHaveSameFingerprint() throws Exception {
        String yaml = "openapi: 3.0.3\ninfo:\n  title: t\n  version: '1'\npaths: {}\n";
        String json = "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"t\",\"version\":\"1\"},\"paths\":{}}";
        assertEquals(Canonicalizer.fingerprint(Canonicalizer.parse("application/yaml", yaml)),
                Canonicalizer.fingerprint(Canonicalizer.parse("application/json", json)));
    }

    @Test
    void differentContentHasDifferentFingerprint() {
        assertNotEquals(
                Canonicalizer.sha256("a"), Canonicalizer.sha256("b"));
    }
}
