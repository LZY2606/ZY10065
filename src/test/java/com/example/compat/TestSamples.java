package com.example.compat;

import com.example.compat.engine.Canonicalizer;
import com.example.compat.engine.SampleModel;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class TestSamples {

    private TestSamples() {
    }

    static List<SampleModel> load() throws IOException {
        String content = Files.readString(
                Path.of("src/test/resources/testdata/samples.json"));
        JsonNode array = Canonicalizer.parse("application/json", content);
        List<SampleModel> samples = new ArrayList<>();
        for (JsonNode node : array) {
            samples.add(new SampleModel(
                    node.get("key").asText(),
                    node.get("method").asText().toUpperCase(),
                    node.get("path").asText(),
                    node.has("statusCode") && node.get("statusCode").isNumber()
                            ? node.get("statusCode").asInt() : null,
                    node.has("requestMediaType") ? node.get("requestMediaType").asText() : null,
                    node.has("responseMediaType") ? node.get("responseMediaType").asText() : null,
                    node.get("requestBody"),
                    node.get("responseBody")));
        }
        return samples;
    }
}
