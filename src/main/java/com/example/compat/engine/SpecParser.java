package com.example.compat.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Parses the supported OpenAPI 3.0.x subset:
 *  - path items with parameter inheritance (operation params merge path-level params)
 *  - parameters with inline or referenced schemas
 *  - requestBody content media types
 *  - responses keyed by status code with "default" fallback
 * References, allOf composition and discriminators are handled by
 * {@link SchemaFlattener}; external refs and cycles surface as evidence.
 */
public final class SpecParser {

    private static final Set<String> METHODS = Set.of(
            "get", "put", "post", "delete", "options", "head", "patch", "trace");

    public SpecModel parse(JsonNode root) {
        if (root == null || !root.has("openapi")) {
            throw new IllegalArgumentException("not an OpenAPI 3.x document (missing openapi version)");
        }
        String openapi = root.get("openapi").asText();
        if (!openapi.startsWith("3.")) {
            throw new IllegalArgumentException("unsupported openapi version: " + openapi);
        }
        RefResolver resolver = new RefResolver(root);
        SchemaFlattener flattener = new SchemaFlattener(resolver);
        TreeMap<String, OperationModel> operations = new TreeMap<>();

        JsonNode paths = root.get("paths");
        if (paths != null && paths.isObject()) {
            List<String> pathNames = new ArrayList<>();
            paths.fieldNames().forEachRemaining(name -> {
                if (!name.startsWith("x-")) {
                    pathNames.add(name);
                }
            });
            Collections.sort(pathNames);
            for (String pathName : pathNames) {
                JsonNode pathItemRaw = paths.get(pathName);
                JsonNode pathItem = resolveInline(pathItemRaw, resolver, new TreeSet<>());
                if (pathItem == null) {
                    continue;
                }
                List<ParamModel> pathParams = parseParameters(
                        pathItem.get("parameters"), resolver, flattener);
                List<String> methodNames = new ArrayList<>();
                pathItem.fieldNames().forEachRemaining(methodNames::add);
                Collections.sort(methodNames);
                for (String method : methodNames) {
                    if (!METHODS.contains(method.toLowerCase())) {
                        continue;
                    }
                    JsonNode operation = pathItem.get(method);
                    List<ParamModel> operationParams = parseParameters(
                            operation.get("parameters"), resolver, flattener);
                    List<ParamModel> merged = mergeParameters(pathParams, operationParams);
                    BodyModel body = null;
                    if (operation.has("requestBody")) {
                        JsonNode bodyNode = resolveInline(operation.get("requestBody"),
                                resolver, new TreeSet<>());
                        if (bodyNode != null) {
                            boolean required = bodyNode.path("required").asBoolean(false);
                            body = new BodyModel(required,
                                    parseMediaTypes(bodyNode.get("content"), flattener));
                        }
                    }
                    TreeMap<String, ResponseModel> responses = new TreeMap<>(
                            new StatusComparator());
                    if (operation.has("responses")) {
                        JsonNode responsesNode = operation.get("responses");
                        List<String> statuses = new ArrayList<>();
                        responsesNode.fieldNames().forEachRemaining(statuses::add);
                        statuses.sort(new StatusComparator());
                        for (String status : statuses) {
                            JsonNode response = resolveInline(responsesNode.get(status),
                                    resolver, new TreeSet<>());
                            if (response == null) {
                                continue;
                            }
                            TreeMap<String, ESchema> mediaTypes =
                                    parseMediaTypes(response.get("content"), flattener);
                            TreeMap<String, ParamModel> headers = new TreeMap<>();
                            if (response.has("headers") && response.get("headers").isObject()) {
                                response.get("headers").fields().forEachRemaining(header -> {
                                    JsonNode headerNode = resolveInline(header.getValue(),
                                            resolver, new TreeSet<>());
                                    if (headerNode != null && headerNode.has("schema")) {
                                        headers.put(header.getKey(), new ParamModel(
                                                header.getKey(), "header",
                                                headerNode.path("required").asBoolean(false),
                                                headerNode.path("deprecated").asBoolean(false),
                                                "simple",
                                                flattener.flatten(headerNode.get("schema"))));
                                    }
                                });
                            }
                            String description = response.has("description")
                                    ? response.get("description").asText() : null;
                            responses.put(status,
                                    new ResponseModel(status, description, mediaTypes, headers));
                        }
                    }
                    String operationId = operation.has("operationId")
                            ? operation.get("operationId").asText() : null;
                    OperationModel model = new OperationModel(method.toUpperCase(), pathName,
                            operationId, List.copyOf(merged), body, responses);
                    operations.put(SpecModel.opKey(method, pathName), model);
                }
            }
        }
        return new SpecModel(openapi, operations, resolver, root);
    }

    private List<ParamModel> mergeParameters(List<ParamModel> pathParams,
                                             List<ParamModel> operationParams) {
        TreeMap<String, ParamModel> byKey = new TreeMap<>();
        for (ParamModel parameter : pathParams) {
            byKey.put(parameter.getIn() + ":" + parameter.getName(), parameter);
        }
        for (ParamModel parameter : operationParams) {
            byKey.put(parameter.getIn() + ":" + parameter.getName(), parameter);
        }
        return new ArrayList<>(byKey.values());
    }

    private List<ParamModel> parseParameters(JsonNode array, RefResolver resolver,
                                             SchemaFlattener flattener) {
        List<ParamModel> parameters = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return parameters;
        }
        for (JsonNode rawParameter : array) {
            JsonNode parameter = resolveInline(rawParameter, resolver, new TreeSet<>());
            if (parameter == null || !parameter.has("name") || !parameter.has("in")) {
                continue;
            }
            ESchema schema = parameter.has("schema")
                    ? flattener.flatten(parameter.get("schema")) : null;
            parameters.add(new ParamModel(
                    parameter.get("name").asText(),
                    parameter.get("in").asText(),
                    parameter.path("required").asBoolean(false),
                    parameter.path("deprecated").asBoolean(false),
                    parameter.has("style") ? parameter.get("style").asText() : null,
                    schema));
        }
        return parameters;
    }

    private TreeMap<String, ESchema> parseMediaTypes(JsonNode content,
                                                     SchemaFlattener flattener) {
        TreeMap<String, ESchema> mediaTypes = new TreeMap<>();
        if (content == null || !content.isObject()) {
            return mediaTypes;
        }
        List<String> names = new ArrayList<>();
        content.fieldNames().forEachRemaining(names::add);
        Collections.sort(names);
        for (String mediaType : names) {
            JsonNode mediaTypeNode = content.get(mediaType);
            if (mediaTypeNode != null && mediaTypeNode.has("schema")) {
                mediaTypes.put(mediaType, flattener.flatten(mediaTypeNode.get("schema")));
            }
        }
        return mediaTypes;
    }

    /** Follows a $ref chain for a parameter/requestBody/response component node. */
    private JsonNode resolveInline(JsonNode node, RefResolver resolver, Set<String> stack) {
        JsonNode current = node;
        while (current != null && current.has("$ref")) {
            String ref = current.get("$ref").asText();
            if (!ref.startsWith("#/") || !stack.add(ref)) {
                return current;
            }
            current = resolver.resolve(ref);
        }
        return current;
    }

    /** Numeric status codes before "default", numeric ascending. */
    static final class StatusComparator implements java.util.Comparator<String> {
        @Override
        public int compare(String left, String right) {
            boolean leftNumeric = left.chars().allMatch(Character::isDigit);
            boolean rightNumeric = right.chars().allMatch(Character::isDigit);
            if (leftNumeric && rightNumeric) {
                return Integer.compare(Integer.parseInt(left), Integer.parseInt(right));
            }
            if (leftNumeric) {
                return -1;
            }
            if (rightNumeric) {
                return 1;
            }
            return left.compareTo(right);
        }
    }

    private static final class Collections {
        static <T extends Comparable<? super T>> void sort(List<T> list) {
            list.sort(java.util.Comparator.naturalOrder());
        }
    }
}
