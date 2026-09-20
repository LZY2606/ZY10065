package com.example.compat.engine;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A sanitized recorded traffic sample.
 * key: caller-stable sample identifier (unique within a snapshot)
 * method/path: the recorded call
 * statusCode: recorded response status (may be null for request-only fixtures)
 * requestMediaType / responseMediaType: declared content types of the bodies
 * requestBody / responseBody: parsed JSON payloads
 */
public record SampleModel(String key, String method, String path, Integer statusCode,
                          String requestMediaType, String responseMediaType,
                          JsonNode requestBody, JsonNode responseBody) {
}
