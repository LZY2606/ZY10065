package com.gsb.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Structural compatibility diff.
 *
 * Direction:
 *  - REQUEST  : what the client must SEND (request bodies, parameters).
 *               Adding constraints is breaking for existing callers.
 *  - RESPONSE : what the client CONSUMES (response bodies).
 *               Removing/relaxing constraints is breaking for consumers.
 *
 * Raw severities follow the current strict policy; the run service reclassifies
 * using the selected policy version before persisting the report.
 */
public final class Differ {

    private Differ() {
    }

    public static List<Change> diffOperation(OperationInfo base, OperationInfo cand) {
        TreeSet<Change> out = new TreeSet<>(Differ::compare);
        diffParameters(base, cand, out);
        diffRequestBodies(base, cand, out);
        diffResponses(base, cand, out);
        return new ArrayList<>(out);
    }

    private static int compare(Change a, Change b) {
        int c = a.direction().compareTo(b.direction());
        if (c != 0) {
            return c;
        }
        c = a.pointer().compareTo(b.pointer());
        if (c != 0) {
            return c;
        }
        c = a.code().compareTo(b.code());
        return c != 0 ? c : a.detail().compareTo(b.detail());
    }

    // ------------------------------------------------------------------
    // parameters
    // ------------------------------------------------------------------

    private static void diffParameters(OperationInfo base, OperationInfo cand, Set<Change> out) {
        Map<String, ParamInfo> baseParams = new java.util.LinkedHashMap<>();
        Map<String, ParamInfo> candParams = new java.util.LinkedHashMap<>();
        base.params().forEach(p -> baseParams.put(p.locator(), p));
        cand.params().forEach(p -> candParams.put(p.locator(), p));

        for (Map.Entry<String, ParamInfo> e : baseParams.entrySet()) {
            ParamInfo c = candParams.get(e.getKey());
            String pointer = "parameter/" + e.getKey();
            if (c == null) {
                // removing a parameter clients may send is usually fine;
                // removing a required PATH parameter is breaking for the call
                boolean breaking = "path".equals(e.getValue().in()) && e.getValue().required();
                out.add(new Change("PARAM_REMOVED", pointer,
                        breaking ? Change.BREAKING : Change.WARNING, Change.REQUEST,
                        "parameter " + e.getKey() + " removed", e.getKey(), null));
                continue;
            }
            if (e.getValue().required() != c.required()) {
                boolean added = c.required();
                out.add(new Change("PARAM_REQUIRED_CHANGED", pointer,
                        added ? Change.BREAKING : Change.COMPATIBLE, Change.REQUEST,
                        "parameter " + e.getKey() + (added ? " became required" : " is no longer required"),
                        e.getValue().required(), c.required()));
            }
            out.addAll(diffSchema(e.getValue().schema(), c.schema(), pointer + "/schema", Change.REQUEST));
        }
        for (String key : candParams.keySet()) {
            if (!baseParams.containsKey(key)) {
                ParamInfo c = candParams.get(key);
                out.add(new Change("PARAM_ADDED", "parameter/" + key,
                        c.required() ? Change.BREAKING : Change.COMPATIBLE, Change.REQUEST,
                        "parameter " + key + " added" + (c.required() ? " as required" : ""),
                        null, key));
            }
        }
    }

    // ------------------------------------------------------------------
    // request body
    // ------------------------------------------------------------------

    private static void diffRequestBodies(OperationInfo base, OperationInfo cand, Set<Change> out) {
        MediaBody b = base.requestBody();
        MediaBody c = cand.requestBody();
        if (b == null) {
            b = MediaBody.ABSENT;
        }
        if (c == null) {
            c = MediaBody.ABSENT;
        }
        if (b.content().isEmpty() && c.content().isEmpty()) {
            if (b.required() != c.required()) {
                out.add(new Change("REQUEST_BODY_REQUIRED_CHANGED", "requestBody",
                        c.required() ? Change.BREAKING : Change.COMPATIBLE, Change.REQUEST,
                        c.required() ? "request body became required" : "request body became optional",
                        b.required(), c.required()));
            }
            return;
        }
        if (b.content().isEmpty()) {
            out.add(new Change("REQUEST_BODY_ADDED", "requestBody",
                    c.required() ? Change.BREAKING : Change.WARNING, Change.REQUEST,
                    "request body added", null, c.content().keySet()));
            return;
        }
        if (c.content().isEmpty()) {
            out.add(new Change("REQUEST_BODY_REMOVED", "requestBody",
                    Change.COMPATIBLE, Change.REQUEST,
                    "request body removed", b.content().keySet(), null));
        }
        diffMedia("requestBody", b, c, Change.REQUEST, out);
        if (b.required() != c.required() && !b.content().isEmpty() && !c.content().isEmpty()) {
            out.add(new Change("REQUEST_BODY_REQUIRED_CHANGED", "requestBody",
                    c.required() ? Change.BREAKING : Change.COMPATIBLE, Change.REQUEST,
                    c.required() ? "request body became required" : "request body became optional",
                    b.required(), c.required()));
        }
    }

    // ------------------------------------------------------------------
    // responses (status-code priority)
    // ------------------------------------------------------------------

    private static void diffResponses(OperationInfo base, OperationInfo cand, Set<Change> out) {
        Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(base.responses().keySet());
        allKeys.addAll(cand.responses().keySet());
        for (String key : allKeys) {
            MediaBody b = base.responses().get(key);
            MediaBody c = cand.responses().get(key);
            String pointer = "response/" + key;
            if (b == null) {
                out.add(new Change("STATUS_ADDED", pointer, Change.COMPATIBLE, Change.RESPONSE,
                        "response status " + key + " added", null, key));
                continue;
            }
            if (c == null) {
                out.add(new Change("STATUS_REMOVED", pointer, Change.BREAKING, Change.RESPONSE,
                        "response status " + key + " removed", key, null));
                continue;
            }
            diffMedia(pointer, b, c, Change.RESPONSE, out);
        }
        detectPriorityShifts(base, cand, out);
    }

    /**
     * Exact status > wildcard (2XX) > default. If a concrete status present in a
     * sample resolves through different priority levels between base and candidate
     * and the resulting schema/media type differs, emit STATUS_PRIORITY_SHIFT.
     */
    private static void detectPriorityShifts(OperationInfo base, OperationInfo cand, Set<Change> out) {
        for (String baseKey : base.responses().keySet()) {
            if (!isConcrete(baseKey)) {
                continue;
            }
            String baseResolved = resolveKey(base.responses(), baseKey);
            String candResolved = resolveKey(cand.responses(), baseKey);
            if (!Objects.equals(baseResolved, candResolved)) {
                MediaBody oldBody = base.responses().get(baseResolved);
                MediaBody newBody = cand.responses().get(candResolved);
                if (!mediaSignature(oldBody).equals(mediaSignature(newBody))) {
                    out.add(new Change("STATUS_PRIORITY_SHIFT", "response/" + baseKey,
                            Change.BREAKING, Change.RESPONSE,
                            "status " + baseKey + " now resolves via '" + candResolved
                                    + "' instead of '" + baseResolved + "'",
                            baseResolved, candResolved));
                }
            }
        }
    }

    public static String resolveKey(Map<String, MediaBody> responses, String status) {
        if (responses.containsKey(status)) {
            return status;
        }
        String wildcard = status.substring(0, 1) + "XX";
        if (responses.containsKey(wildcard)) {
            return wildcard;
        }
        if (responses.containsKey("default")) {
            return "default";
        }
        return null;
    }

    private static boolean isConcrete(String key) {
        return key.matches("\\d{3}");
    }

    private static String mediaSignature(MediaBody body) {
        if (body == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String media : new TreeSet<>(body.content().keySet())) {
            parts.add(media + ":" + nodeSignature(body.content().get(media)));
        }
        return String.join("|", parts);
    }

    private static String nodeSignature(SchemaNode node) {
        if (node == null) {
            return "null";
        }
        if (node.isTrouble()) {
            return node.kind() + ":" + node.ref();
        }
        StringBuilder sb = new StringBuilder(node.kind());
        sb.append(",req=").append(node.required());
        sb.append(",nullable=").append(node.nullable());
        sb.append(",enum=").append(node.enumValues());
        sb.append(",min=").append(node.minimum()).append(",max=").append(node.maximum());
        sb.append(",default=").append(node.defaultValue());
        node.properties().forEach((k, v) -> sb.append(",").append(k).append("=").append(nodeSignature(v)));
        return sb.toString();
    }

    private static void diffMedia(String pointer, MediaBody base, MediaBody cand,
                                  String direction, Set<Change> out) {
        Set<String> media = new TreeSet<>();
        media.addAll(base.content().keySet());
        media.addAll(cand.content().keySet());
        for (String type : media) {
            SchemaNode b = base.content().get(type);
            SchemaNode c = cand.content().get(type);
            if (b == null) {
                out.add(new Change("MEDIA_ADDED", pointer + "/" + type,
                        Change.REQUEST.equals(direction) ? Change.COMPATIBLE : Change.WARNING,
                        direction, "media type " + type + " added", null, type));
                continue;
            }
            if (c == null) {
                out.add(new Change("MEDIA_REMOVED", pointer + "/" + type,
                        Change.REQUEST.equals(direction) ? Change.WARNING : Change.BREAKING,
                        direction, "media type " + type + " removed", type, null));
                continue;
            }
            out.addAll(diffSchema(b, c, pointer + "/" + type, direction));
        }
    }

    // ------------------------------------------------------------------
    // schema diff
    // ------------------------------------------------------------------

    public static List<Change> diffSchema(SchemaNode base, SchemaNode cand, String pointer, String direction) {
        TreeSet<Change> out = new TreeSet<>(Differ::compare);
        diffSchemaInto(base, cand, pointer, direction, out, new HashSet<>());
        return new ArrayList<>(out);
    }

    private static void diffSchemaInto(SchemaNode base, SchemaNode cand, String pointer,
                                       String direction, Set<Change> out, Set<String> visited) {
        if (base == null || cand == null) {
            return;
        }
        if (base.isTrouble() || cand.isTrouble()) {
            if (!Objects.equals(base.kind(), cand.kind()) || !Objects.equals(base.ref(), cand.ref())) {
                out.add(new Change("SCHEMA_RESOLUTION_CHANGED", pointer,
                        Change.WARNING, direction,
                        "schema resolution changed: " + base.kind() + " -> " + cand.kind(),
                        base.ref(), cand.ref()));
            }
            return;
        }
        if (!Objects.equals(base.kind(), cand.kind())
                && !isObjectLike(base) && !isObjectLike(cand)) {
            out.add(new Change("TYPE_CHANGED", pointer, Change.BREAKING, direction,
                    "type changed from " + base.kind() + " to " + cand.kind(),
                    base.kind(), cand.kind()));
            return;
        }
        if (isObjectLike(base) && isObjectLike(cand)) {
            diffObject(base, cand, pointer, direction, out, visited);
        } else if (!Objects.equals(base.kind(), cand.kind())) {
            out.add(new Change("TYPE_CHANGED", pointer, Change.BREAKING, direction,
                    "type changed from " + base.kind() + " to " + cand.kind(),
                    base.kind(), cand.kind()));
            return;
        }
        diffConstraints(base, cand, pointer, direction, out);
        if (SchemaNode.ARRAY.equals(base.kind()) && SchemaNode.ARRAY.equals(cand.kind())) {
            diffSchemaInto(base.items(), cand.items(), pointer + "[]", direction, out, visited);
        }
        if ((SchemaNode.ONE_OF.equals(base.kind()) || SchemaNode.ANY_OF.equals(base.kind()))
                && base.kind().equals(cand.kind())) {
            diffAlternatives(base, cand, pointer, direction, out, visited);
        }
    }

    private static boolean isObjectLike(SchemaNode node) {
        return SchemaNode.OBJECT.equals(node.kind()) || SchemaNode.EMPTY.equals(node.kind())
                || SchemaNode.ALL_OF.equals(node.kind());
    }

    private static void diffObject(SchemaNode base, SchemaNode cand, String pointer,
                                   String direction, Set<Change> out, Set<String> visited) {
        Set<String> props = new TreeSet<>();
        props.addAll(base.properties().keySet());
        props.addAll(cand.properties().keySet());
        Set<String> baseRequired = new HashSet<>(base.required());
        Set<String> candRequired = new HashSet<>(cand.required());

        for (String name : props) {
            String childPointer = pointer + "." + name;
            SchemaNode b = base.properties().get(name);
            SchemaNode c = cand.properties().get(name);
            if (b == null) {
                // field added
                boolean required = candRequired.contains(name);
                String code = required ? "REQUIRED_ADDED" : "FIELD_ADDED";
                String severity;
                if (Change.REQUEST.equals(direction)) {
                    severity = required ? Change.BREAKING : Change.COMPATIBLE;
                } else {
                    severity = required ? Change.BREAKING : Change.COMPATIBLE;
                }
                out.add(new Change(code, childPointer, severity, direction,
                        "property '" + name + "' added" + (required ? " as required" : ""),
                        null, required ? "required" : "optional"));
                continue;
            }
            if (c == null) {
                boolean wasRequired = baseRequired.contains(name);
                out.add(new Change("FIELD_REMOVED", childPointer,
                        Change.REQUEST.equals(direction)
                                ? (wasRequired ? Change.WARNING : Change.COMPATIBLE)
                                : Change.BREAKING,
                        direction,
                        (Change.REQUEST.equals(direction)
                                ? "request property '" + name + "' no longer accepted"
                                : "response property '" + name + "' consumers rely on was removed"),
                        wasRequired ? "required" : "optional", null));
                continue;
            }
            boolean bReq = baseRequired.contains(name);
            boolean cReq = candRequired.contains(name);
            if (bReq != cReq) {
                out.add(new Change(cReq ? "REQUIRED_ADDED" : "REQUIRED_REMOVED", childPointer,
                        requiredSeverity(cReq, direction), direction,
                        "property '" + name + "' " + (cReq ? "became required" : "is no longer required"),
                        bReq, cReq));
            }
            diffSchemaInto(b, c, childPointer, direction, out, visited);
        }

        // required props added without a property entry edge handled above already
    }

    private static String requiredSeverity(boolean added, String direction) {
        if (added) {
            return Change.BREAKING;
        }
        return Change.REQUEST.equals(direction) ? Change.COMPATIBLE : Change.WARNING;
    }

    private static SchemaNode emptyNode() {
        return new SchemaNode(SchemaNode.EMPTY, Map.of(), List.of(), null, null,
                null, List.of(), null, null, null, null, null, null, null,
                null, List.of(), null);
    }

    private static void diffAlternatives(SchemaNode base, SchemaNode cand, String pointer,
                                         String direction, Set<Change> out, Set<String> visited) {
        List<String> baseSigs = new ArrayList<>(base.alternatives().stream().map(Differ::nodeSignature).sorted().toList());
        List<String> candSigs = new ArrayList<>(cand.alternatives().stream().map(Differ::nodeSignature).sorted().toList());
        Set<String> removed = new TreeSet<>(baseSigs);
        candSigs.forEach(removed::remove);
        Set<String> added = new TreeSet<>(candSigs);
        baseSigs.forEach(added::remove);
        if (!removed.isEmpty()) {
            out.add(new Change("ALTERNATIVE_REMOVED", pointer, Change.WARNING, direction,
                    (Change.REQUEST.equals(direction) ? "request" : "response")
                            + " alternative branch removed", removed, null));
        }
        if (!added.isEmpty()) {
            out.add(new Change("ALTERNATIVE_ADDED", pointer, Change.COMPATIBLE, direction,
                    "alternative branch added", null, added));
        }
    }

    private static void diffConstraints(SchemaNode base, SchemaNode cand, String pointer,
                                        String direction, Set<Change> out) {
        boolean baseNullable = Boolean.TRUE.equals(base.nullable());
        boolean candNullable = Boolean.TRUE.equals(cand.nullable());
        if (baseNullable != candNullable) {
            boolean becameNullable = !baseNullable && candNullable;
            out.add(new Change("NULLABILITY_CHANGED", pointer,
                    Change.REQUEST.equals(direction)
                            // request: becoming nullable relaxes what callers send;
                            // forbidding null is breaking for existing callers
                            ? (becameNullable ? Change.COMPATIBLE : Change.BREAKING)
                            // response: consumers may already handle null values;
                            // a newly nullable field breaks code assuming non-null
                            : (becameNullable ? Change.BREAKING : Change.COMPATIBLE),
                    direction,
                    "nullability changed from " + baseNullable + " to " + candNullable,
                    baseNullable, candNullable));
        }

        if (!Objects.equals(base.defaultValue(), cand.defaultValue())
                && (base.defaultValue() != null || cand.defaultValue() != null)) {
            out.add(new Change("DEFAULT_CHANGED", pointer, Change.WARNING, direction,
                    "default value changed", base.defaultValue(), cand.defaultValue()));
        }

        if (!base.enumValues().isEmpty() || !cand.enumValues().isEmpty()) {
            Set<Object> baseEnum = new TreeSet<>(enumComparable(base.enumValues()));
            Set<Object> candEnum = new TreeSet<>(enumComparable(cand.enumValues()));
            Set<Object> removed = new TreeSet<>(baseEnum);
            removed.removeAll(candEnum);
            Set<Object> added = new TreeSet<>(candEnum);
            added.removeAll(baseEnum);
            if (!removed.isEmpty()) {
                out.add(new Change("ENUM_NARROWED", pointer, Change.BREAKING, direction,
                        "allowed enum values removed: " + removed,
                        baseEnum, candEnum));
            }
            if (!added.isEmpty() && baseEnum.isEmpty() && !candEnum.isEmpty()) {
                out.add(new Change("ENUM_NARROWED", pointer, Change.BREAKING, direction,
                        "enum constraint added", baseEnum, candEnum));
            } else if (!added.isEmpty()) {
                out.add(new Change("ENUM_WIDENED", pointer,
                        Change.REQUEST.equals(direction) ? Change.COMPATIBLE : Change.WARNING,
                        direction, "new enum values allowed: " + added, baseEnum, candEnum));
            }
        }

        diffBounds(base, cand, pointer, direction, out,
                SchemaNode::minimum, "MINIMUM", true);
        diffBounds(base, cand, pointer, direction, out,
                SchemaNode::maximum, "MAXIMUM", false);
        diffLength(base, cand, pointer, direction, out, true);
        diffLength(base, cand, pointer, direction, out, false);
    }

    private static Set<Object> enumComparable(List<Object> values) {
        Set<Object> out = new TreeSet<>(new EnumOrder());
        out.addAll(values);
        return out;
    }

    private static final class EnumOrder implements java.util.Comparator<Object> {
        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public int compare(Object a, Object b) {
            if (a instanceof Comparable && a.getClass() == b.getClass()) {
                return ((Comparable) a).compareTo(b);
            }
            return String.valueOf(a).compareTo(String.valueOf(b));
        }
    }

    private static void diffBounds(SchemaNode base, SchemaNode cand, String pointer,
                                   String direction, Set<Change> out,
                                   java.util.function.Function<SchemaNode, Double> getter,
                                   String code, boolean isMin) {
        Double b = getter.apply(base);
        Double c = getter.apply(cand);
        if (Objects.equals(b, c)) {
            return;
        }
        boolean tightening;
        String word;
        if (isMin) {
            tightening = c != null && (b == null || c > b);
            word = "minimum";
        } else {
            tightening = c != null && (b == null || c < b);
            word = "maximum";
        }
        String severity;
        if (c == null) {
            severity = Change.REQUEST.equals(direction) ? Change.COMPATIBLE : Change.WARNING;
        } else if (tightening) {
            severity = Change.REQUEST.equals(direction) ? Change.BREAKING : Change.COMPATIBLE;
        } else {
            severity = Change.REQUEST.equals(direction) ? Change.COMPATIBLE : Change.BREAKING;
        }
        String suffix = isMin ? "_TIGHTENED" : "_TIGHTENED";
        if (!isMin) {
            suffix = "_TIGHTENED";
        }
        out.add(new Change(code + suffix, pointer, severity, direction,
                word + " changed from " + b + " to " + c, b, c));
    }

    private static void diffLength(SchemaNode base, SchemaNode cand, String pointer,
                                   String direction, Set<Change> out, boolean isMin) {
        Integer b = isMin ? base.minLength() : base.maxLength();
        Integer c = isMin ? cand.minLength() : cand.maxLength();
        if (Objects.equals(b, c)) {
            return;
        }
        boolean tightening;
        String word;
        if (isMin) {
            tightening = c != null && (b == null || c > b);
            word = "minLength";
        } else {
            tightening = c != null && (b == null || c < b);
            word = "maxLength";
        }
        String severity;
        if (c == null) {
            severity = Change.REQUEST.equals(direction) ? Change.COMPATIBLE : Change.WARNING;
        } else if (tightening) {
            severity = Change.REQUEST.equals(direction) ? Change.BREAKING : Change.COMPATIBLE;
        } else {
            severity = Change.REQUEST.equals(direction) ? Change.COMPATIBLE : Change.BREAKING;
        }
        out.add(new Change((isMin ? "MIN_LENGTH" : "MAX_LENGTH") + "_CHANGED", pointer,
                severity, direction, word + " changed from " + b + " to " + c, b, c));
    }
}
