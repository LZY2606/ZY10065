package com.gsb.engine;

public record Fingerprint(String sha256, long bytes, String openapi, String title, String version) {
}
