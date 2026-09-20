package com.gsb.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Parses OpenAPI 3.x JSON/YAML into resolved {@link SchemaNode} trees.
 *
 * Reference handling:
 *  - only document-local refs (#/components/...) are followed
 *  - external refs and unresolved local refs become terminal REF_UNRESOLVED nodes
 *  - a reference whose chain is already active becomes a terminal CYCLE node
 * Unresolved refs and cycles are also collected as {@link ParseIssue} evidence.
 */
public final class SpecParser {

    private static final Set<String> METHODS = Set.of(
            "get", "put", "post", "delete", "options", "head", "patch", "trace");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JsonNode root;
    private final List<ParseIssue> issues = new ArrayList<>();

    private SpecParser(JsonNode root) {
        this.root = root;
    }

    public static Fingerprint fingerprint(String rawText) throws IOException {
        JsonNode tree = parseTree(rawText);
        JsonNode info = tree.path("info");
        return new Fingerprint(
                Canonical.sha256Text(rawText),
                rawText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                text(tree.path("openapi")),
                text(info.path("title")),
                text(info.path("version")));
    }

    public static ParsedSpec parse(String rawText) throws IOException {
        SpecParser parser = new SpecParser(parseTree(rawText));
        return new ParsedSpec(parser.build(), List.copyOf(parser.issues));
    }

    private static JsonNode parseTree(String rawText) throws IOException {
        String trimmed = rawText.stripLeading();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return JSON.readTree(rawText);
        }
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        return yaml.readTree(rawText);
    }

    private static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? "" : node.asText("");
    }

    private OpenApiDoc build() {
        Map<String, OperationInfo> operations = new TreeMap<>();
        JsonNode paths = root.path("paths");
        if (!paths.isObject()) {
            issues.add(new ParseIssue(ParseIssue.BAD_SPEC, "#/paths", "missing or invalid paths object"));
            return new OpenApiDoc(operations, issues);
        }
        for (Map.Entry<String, JsonNode> pathEntry : sorted(paths)) {
            String path = pathEntry.getKey();
            JsonNode pathItem = pathEntry.getValue();
            if (pathItem == null || !pathItem.isObject()) {
                continue;
            }
            List<ParamInfo> inherited = parseParameters(pathItem.path("parameters"), path);
            for (Map.Entry<String, JsonNode> opEntry : sorted(pathItem)) {
                if (!METHODS.contains(opEntry.getKey())) {
                    continue;
                }
                String method = opEntry.getKey().toUpperCase();
                JsonNode op = opEntry.getValue();
                List<ParamInfo> params = mergeParameters(inherited,
                        parseParameters(op.path("parameters"), path + "/" + method.toLowerCase()));
                MediaBody requestBody = parseMediaBody(op.path("requestBody"));
                Map<String, MediaBody> responses = parseResponses(op.path("responses"),
                        path + "/" + method.toLowerCase() + "/responses");
                OperationInfo info = new OperationInfo(method, path,
                        text(op.path("operationId")), List.copyOf(params), requestBody,
                        Map.copyOf(sortMedia(responses)));
                operations.put(info.key(), info);
            }
        }
        return new OpenApiDoc(operations, issues);
    }

    private List<ParamInfo> parseParameters(JsonNode array, String pointer) {
        List<ParamInfo> params = new ArrayList<>();
        if (!array.isArray()) {
            return params;
        }
        for (int i = 0; i < array.size(); i++) {
            JsonNode p = array.get(i);
            String name = text(p.path("name"));
            String in = text(p.path("in"));
            JsonNode resolved = p;
            if (p.has("$ref")) {
                String ref = p.get("$ref").asText();
                if (ref.startsWith("#/")) {
                    JsonNode target = navigate(ref);
                    if (target != null && !target.isMissingNode()) {
                        resolved = target;
                    } else {
                        addIssue(ParseIssue.UNRESOLVED_REF, pointer, "unresolved parameter ref: " + ref);
                    }
                } else {
                    addIssue(ParseIssue.UNRESOLVED_REF, pointer, "external parameter ref: " + ref);
                }
            }
            String finalName = text(resolved.path("name"));
            String finalIn = text(resolved.path("in"));
            boolean required = resolved.path("required").asBoolean(false);
            String style = text(resolved.path("style"));
            SchemaNode schema = resolveSchema(resolved.path("schema"),
                    pointer + "/" + i + "/schema", new ArrayDeque<>());
            params.add(new ParamInfo(finalName, finalIn, required, style, schema));
        }
        return params;
    }

    private List<ParamInfo> mergeParameters(List<ParamInfo> inherited, List<ParamInfo> own) {
        Map<String, ParamInfo> merged = new LinkedHashMap<>();
        for (ParamInfo p : inherited) {
            merged.put(p.locator(), p);
        }
        for (ParamInfo p : own) {
            merged.put(p.locator(), p); // operation-level overrides path-level
        }
        return new ArrayList<>(merged.values());
    }

    private MediaBody parseMediaBody(JsonNode bodyNode) {
        if (bodyNode == null || !bodyNode.isObject()) {
            return MediaBody.ABSENT;
        }
        boolean required = bodyNode.path("required").asBoolean(false);
        return new MediaBody(parseContent(bodyNode.path("content"),
                new ArrayDeque<>()), required);
    }

    private Map<String, SchemaNode> parseContent(JsonNode content, Deque<String> stack) {
        Map<String, SchemaNode> out = new TreeMap<>();
        if (content == null || !content.isObject()) {
            return out;
        }
        for (Map.Entry<String, JsonNode> media : sorted(content)) {
            SchemaNode node = resolveSchema(media.getValue().path("schema"),
                    "#/content/" + media.getKey(), new ArrayDeque<>(stack));
            out.put(media.getKey(), node);
        }
        return out;
    }

    private Map<String, MediaBody> parseResponses(JsonNode responses, String pointer) {
        Map<String, MediaBody> out = new TreeMap<>();
        if (!responses.isObject()) {
            return out;
        }
        for (Map.Entry<String, JsonNode> entry : sorted(responses)) {
            JsonNode response = entry.getValue();
            Map<String, SchemaNode> content = parseContent(response.path("content"),
                    new ArrayDeque<>());
            out.put(entry.getKey(), new MediaBody(content, false));
        }
        return pointer.isEmpty() ? out : out;
    }

    private static Map<String, MediaBody> sortMedia(Map<String, MediaBody> in) {
        return new TreeMap<>(in);
    }

    private static List<Map.Entry<String, JsonNode>> sorted(JsonNode object) {
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
        object.fields().forEachRemaining(entries::add);
        entries.sort(Map.Entry.comparingByKey());
        return entries;
    }

    private SchemaNode resolveSchema(JsonNode node, String pointer, Deque<String> stack) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return new SchemaNode(SchemaNode.EMPTY, Map.of(), List.of(), null,
                    null, null, List.of(), null, null, null, null, null, null,
                    null, null, List.of(), null);
        }
        if (node.has("$ref")) {
            String ref = node.get("$ref").asText();
            if (!ref.startsWith("#/")) {
                addIssue(ParseIssue.UNRESOLVED_REF, pointer, "external ref not supported: " + ref);
                return trouble(SchemaNode.REF_UNRESOLVED, ref);
            }
            if (stack.contains(ref)) {
                addIssue(ParseIssue.CYCLE, pointer, "cyclic reference: " + ref);
                return trouble(SchemaNode.CYCLE, ref);
            }
            JsonNode target = navigate(ref);
            if (target == null || target.isMissingNode()) {
                addIssue(ParseIssue.UNRESOLVED_REF, pointer, "unresolved ref: " + ref);
                return trouble(SchemaNode.REF_UNRESOLVED, ref);
            }
            Deque<String> next = new ArrayDeque<>(stack);
            next.push(ref);
            return resolveSchema(target, ref, next);
        }

        String type = node.path("type").asText("");
        if (node.has("allOf")) {
            return resolveAllOf(node, pointer, stack);
        }
        if (node.has("oneOf") || node.has("anyOf")) {
            return resolveAlternatives(node, pointer, stack);
        }
        return switch (type) {
            case "object" -> resolveObject(node, pointer, stack);
            case "array" -> new SchemaNode(SchemaNode.ARRAY, Map.of(), List.of(),
                    resolveSchema(node.path("items"), pointer + "/items", stack),
                    nullable(node), null, enumList(node, type), null, null, null, null,
                    null, null, null, null, List.of(), null);
            case "string" -> scalar(SchemaNode.STRING, node, type, pointer, stack);
            case "number" -> scalar(SchemaNode.NUMBER, node, type, pointer, stack);
            case "integer" -> scalar(SchemaNode.INTEGER, node, type, pointer, stack);
            case "boolean" -> scalar(SchemaNode.BOOLEAN, node, type, pointer, stack);
            case "null" -> base(SchemaNode.NULL, node, type);
            default -> resolveObject(node, pointer, stack);
        };
    }

    private SchemaNode resolveObject(JsonNode node, String pointer, Deque<String> stack) {
        JsonNode propsNode = node.path("properties");
        Map<String, SchemaNode> props = new TreeMap<>();
        if (propsNode.isObject()) {
            for (Map.Entry<String, JsonNode> p : sorted(propsNode)) {
                props.put(p.getKey(),
                        resolveSchema(p.getValue(), pointer + "/properties/" + p.getKey(), stack));
            }
        }
        List<String> required = new ArrayList<>();
        JsonNode reqNode = node.path("required");
        if (reqNode.isArray()) {
            reqNode.forEach(r -> required.add(r.asText()));
            required.sort(String::compareTo);
        }
        String discriminator = null;
        Map<String, String> mapping = Map.of();
        JsonNode disc = node.path("discriminator");
        if (disc.isObject()) {
            discriminator = text(disc.path("propertyName"));
            JsonNode mapNode = disc.path("mapping");
            if (mapNode.isObject()) {
                mapping = new TreeMap<>();
                for (Map.Entry<String, JsonNode> m : sorted(mapNode)) {
                    mapping.put(m.getKey(), m.getValue().asText());
                }
            }
        }
        return new SchemaNode(props.isEmpty() && typeMissing(node) ? SchemaNode.EMPTY : SchemaNode.OBJECT,
                Map.copyOf(props), List.copyOf(required), null,
                nullable(node), defaultNode(node), enumList(node, "object"),
                null, null, null, null,
                intOrNull(node, "minLength"), intOrNull(node, "maxLength"),
                discriminator, Map.copyOf(mapping), List.of(), null);
    }

    private SchemaNode scalar(String kind, JsonNode node, String type, String pointer, Deque<String> stack) {
        SchemaNode base = base(kind, node, type);
        Double min = doubleOrNull(node, "minimum");
        Double max = doubleOrNull(node, "maximum");
        Boolean exclMin = boolOrNull(node, "exclusiveMinimum");
        Boolean exclMax = boolOrNull(node, "exclusiveMaximum");
        JsonNode exclMinNode = node.get("exclusiveMinimum");
        JsonNode exclMaxNode = node.get("exclusiveMaximum");
        if (exclMinNode != null && exclMinNode.isNumber()) {
            min = exclMinNode.asDouble();
            exclMin = Boolean.TRUE;
        }
        if (exclMaxNode != null && exclMaxNode.isNumber()) {
            max = exclMaxNode.asDouble();
            exclMax = Boolean.TRUE;
        }
        return new SchemaNode(kind, Map.of(), List.of(), null, base.nullable(),
                base.defaultValue(), base.enumValues(), min, max, exclMin, exclMax,
                base.minLength(), base.maxLength(), null, null, List.of(), null);
    }

    private SchemaNode base(String kind, JsonNode node, String type) {
        Integer minLen = kind.equals(SchemaNode.STRING) ? intOrNull(node, "minLength") : null;
        Integer maxLen = kind.equals(SchemaNode.STRING) ? intOrNull(node, "maxLength") : null;
        return new SchemaNode(kind, Map.of(), List.of(), null, nullable(node),
                defaultNode(node), enumList(node, type), null, null, null, null,
                minLen, maxLen, null, null, List.of(), null);
    }

    private SchemaNode resolveAllOf(JsonNode node, String pointer, Deque<String> stack) {
        JsonNode all = node.path("allOf");
        Map<String, SchemaNode> props = new TreeMap<>();
        List<String> required = new ArrayList<>();
        boolean sawObject = false;
        for (int i = 0; i < all.size(); i++) {
            SchemaNode part = resolveSchema(all.get(i), pointer + "/allOf/" + i, stack);
            if (part.isTrouble()) {
                return part;
            }
            if (SchemaNode.OBJECT.equals(part.kind())) {
                sawObject = true;
                props.putAll(part.properties());
                for (String r : part.required()) {
                    if (!required.contains(r)) {
                        required.add(r);
                    }
                }
            }
        }
        required.sort(String::compareTo);
        String kind = sawObject ? SchemaNode.OBJECT
                : (props.isEmpty() ? SchemaNode.EMPTY : SchemaNode.OBJECT);
        return new SchemaNode(kind, Map.copyOf(props), List.copyOf(required), null,
                nullable(node), defaultNode(node), List.of(), null, null, null, null,
                null, null, null, null, List.of(), null);
    }

    private SchemaNode resolveAlternatives(JsonNode node, String pointer, Deque<String> stack) {
        JsonNode array = node.has("oneOf") ? node.path("oneOf") : node.path("anyOf");
        String kind = node.has("oneOf") ? SchemaNode.ONE_OF : SchemaNode.ANY_OF;
        List<SchemaNode> alts = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            alts.add(resolveSchema(array.get(i), pointer + "/" + (kind.equals(SchemaNode.ONE_OF) ? "oneOf" : "anyOf") + "/" + i, stack));
        }
        String discriminator = null;
        Map<String, String> mapping = Map.of();
        JsonNode disc = node.path("discriminator");
        if (disc.isObject()) {
            discriminator = text(disc.path("propertyName"));
            JsonNode mapNode = disc.path("mapping");
            if (mapNode.isObject()) {
                mapping = new TreeMap<>();
                for (Map.Entry<String, JsonNode> m : sorted(mapNode)) {
                    mapping.put(m.getKey(), m.getValue().asText());
                }
            }
        }
        return new SchemaNode(kind, Map.of(), List.of(), null,
                nullable(node), defaultNode(node), List.of(), null, null, null, null,
                null, null, discriminator, Map.copyOf(mapping), List.copyOf(alts), null);
    }

    private SchemaNode trouble(String kind, String ref) {
        return new SchemaNode(kind, Map.of(), List.of(), null, null, null,
                List.of(), null, null, null, null, null, null, null, null,
                List.of(), ref);
    }

    private void addIssue(String kind, String pointer, String detail) {
        for (ParseIssue issue : issues) {
            if (issue.kind().equals(kind) && issue.pointer().equals(pointer)
                    && issue.detail().equals(detail)) {
                return;
            }
        }
        issues.add(new ParseIssue(kind, pointer, detail));
    }

    private JsonNode navigate(String ref) {
        JsonNode current = root;
        String[] parts = ref.substring(2).split("/");
        for (String rawPart : parts) {
            String part = rawPart.replace("~1", "/").replace("~0", "~");
            current = current.path(part);
            if (current.isMissingNode()) {
                return null;
            }
        }
        return current;
    }

    private static boolean typeMissing(JsonNode node) {
        return !node.has("type") && !node.has("properties") && !node.has("allOf")
                && !node.has("oneOf") && !node.has("anyOf") && !node.has("$ref");
    }

    private static Boolean nullable(JsonNode node) {
        return node.path("nullable").asBoolean(false) ? Boolean.TRUE : null;
    }

    private static Object defaultNode(JsonNode node) {
        JsonNode value = node.get("default");
        return value == null || value.isNull() ? null : JSON.convertValue(value, Object.class);
    }

    private static List<Object> enumList(JsonNode node, String type) {
        JsonNode array = node.get("enum");
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<Object> values = new ArrayList<>();
        array.forEach(v -> values.add(v.isNull() ? null : JSON.convertValue(v, Object.class)));
        return List.copyOf(values);
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? null : value.asDouble();
    }

    private static Boolean boolOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isBoolean() ? null : value.asBoolean();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isIntegralNumber() ? null : value.asInt();
    }
}
