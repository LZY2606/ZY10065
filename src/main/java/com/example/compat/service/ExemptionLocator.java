package com.example.compat.service;

import com.example.compat.engine.ESchema;
import com.example.compat.engine.OperationModel;
import com.example.compat.engine.ResponseModel;
import com.example.compat.engine.SpecModel;

import java.util.Map;
import java.util.TreeMap;

/**
 * Re-resolves an exemption's structural anchor inside a later candidate spec.
 *
 * Matching is structural (method + exact path + traversed schema locations),
 * never by name similarity: if the candidate changes so the location no longer
 * exists (property renamed/removed, media type gone, response status gone),
 * resolution fails and the exemption becomes PENDING.
 */
public final class ExemptionLocator {

    public enum Resolution {
        ACTIVE, PENDING
    }

    public record Result(Resolution resolution, String detail) {
    }

    public Result resolve(SpecModel candidate, String method, String path,
                          String anchor, String subject) {
        OperationModel operation = candidate.getOperations()
                .get(SpecModel.opKey(method, path));
        if (operation == null) {
            return new Result(Resolution.PENDING,
                    "operation no longer exists in candidate; cannot locate change");
        }
        if (anchor == null || anchor.isEmpty() || "op".equals(anchor)) {
            return new Result(Resolution.ACTIVE, "operation-level anchor resolved");
        }
        if ("parameters".equals(anchor)) {
            return resolveParameter(operation, subject);
        }
        if ("requestBody".equals(anchor)) {
            return resolveRequestBody(operation, subject);
        }
        if ("responses".equals(anchor)) {
            return resolveResponse(operation, subject);
        }
        return new Result(Resolution.PENDING, "unknown anchor: " + anchor);
    }

    private Result resolveParameter(OperationModel operation, String subject) {
        if (subject == null || subject.isEmpty()) {
            return new Result(Resolution.PENDING, "parameter locator empty");
        }
        String rest = subject.startsWith("parameters/")
                ? subject.substring("parameters/".length()) : subject;
        String name = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
        boolean found = operation.getParameters().stream()
                .anyMatch(parameter -> parameter.getName().equals(name));
        return found
                ? new Result(Resolution.ACTIVE, "parameter '" + name + "' still present")
                : new Result(Resolution.PENDING,
                        "parameter '" + name + "' disappeared; similarity matching is not used");
    }

    private Result resolveRequestBody(OperationModel operation, String subject) {
        if (operation.getRequestBody() == null) {
            return new Result(Resolution.PENDING, "requestBody removed from candidate");
        }
        if (subject == null || subject.isEmpty()) {
            return new Result(Resolution.ACTIVE, "requestBody anchor resolved");
        }
        return resolveContent(operation.getRequestBody().getMediaTypes(), subject, "requestBody");
    }

    private Result resolveResponse(OperationModel operation, String subject) {
        if (subject == null || subject.isEmpty()) {
            return new Result(Resolution.PENDING, "response locator empty");
        }
        String rest = subject.startsWith("responses/")
                ? subject.substring("responses/".length()) : subject;
        String status = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
        ResponseModel response = operation.getResponses().get(status);
        if (response == null) {
            return new Result(Resolution.PENDING,
                    "response status " + status + " disappeared; cannot bind exemption");
        }
        String tail = rest.contains("/") ? rest.substring(rest.indexOf('/') + 1) : "";
        if (tail.isEmpty()) {
            return new Result(Resolution.ACTIVE, "status " + status + " still present");
        }
        if (tail.startsWith("headers/")) {
            String header = tail.substring("headers/".length());
            header = header.contains("/") ? header.substring(0, header.indexOf('/')) : header;
            return response.getHeaders().containsKey(header)
                    ? new Result(Resolution.ACTIVE, "header '" + header + "' still present")
                    : new Result(Resolution.PENDING,
                            "response header '" + header + "' disappeared");
        }
        return resolveContent(response.getMediaTypes(), tail, "responses/" + status);
    }

    private Result resolveContent(TreeMap<String, ESchema> mediaTypes, String subject,
                                  String context) {
        String marker = "content/";
        int markerIndex = subject.indexOf(marker);
        if (markerIndex < 0) {
            return new Result(Resolution.ACTIVE, context + " anchor resolved");
        }
        String rest = subject.substring(markerIndex + marker.length());
        String mediaType = rest.contains("/schema") ? rest.substring(0, rest.indexOf("/schema"))
                : (rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest);
        ESchema schema = mediaTypes.get(mediaType);
        if (schema == null) {
            return new Result(Resolution.PENDING,
                    "media type " + mediaType + " disappeared from " + context);
        }
        if (!rest.contains("/schema")) {
            return new Result(Resolution.ACTIVE, "media type " + mediaType + " present");
        }
        String pointer = rest.substring(rest.indexOf("/schema") + "/schema".length());
        return traverse(schema, pointer, mediaType);
    }

    /**
     * Walks the JSON-pointer-ish suffix (/properties/name, /items, ...).
     * Removed fields fail resolution; that is exactly when the waiver goes pending.
     */
    private Result traverse(ESchema schema, String pointer, String mediaType) {
        ESchema current = schema;
        String[] segments = pointer.split("/");
        StringBuilder walked = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty()) {
                continue;
            }
            walked.append('/').append(segment);
            if ("items".equals(segment)) {
                current = current.getItems();
            } else if ("properties".equals(segment)) {
                if (i + 1 >= segments.length) {
                    current = null;
                } else {
                    String propertyName = segments[++i];
                    walked.append('/').append(propertyName);
                    current = current.getProperties().get(propertyName);
                }
            } else {
                current = null;
            }
            if (current == null) {
                return new Result(Resolution.PENDING,
                        "schema location " + walked + " missing in media type " + mediaType);
            }
            if (current.hasBlockingMarker()) {
                return new Result(Resolution.PENDING,
                        "schema location " + walked + " is unresolvable/cyclic");
            }
        }
        return new Result(Resolution.ACTIVE,
                "schema location " + pointer + " still present in " + mediaType);
    }
}
