package com.example.compat.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Compares two parsed specs and emits findings at stable locators.
 *
 * The output is a pure function of (baseline, candidate, policy): same inputs,
 * same findings, in the same order, with deterministic F-ids.
 */
public final class Differ {

    private final CompatibilityPolicy policy;
    private final List<Finding> findings = new ArrayList<>();

    public Differ(CompatibilityPolicy policy) {
        this.policy = policy;
    }

    public List<Finding> diff(SpecModel baseline, SpecModel candidate) {
        Set<String> allKeys = new TreeSetOfKeys(baseline, candidate);
        for (String key : allKeys) {
            OperationModel oldOp = baseline.getOperations().get(key);
            OperationModel newOp = candidate.getOperations().get(key);
            if (oldOp == null) {
                emit(ChangeCodes.OP_ADDED, Side.REQUEST, newOp, "op", "",
                        null, "operation added", Map.of(), opContext(newOp));
                continue;
            }
            if (newOp == null) {
                emit(ChangeCodes.OP_REMOVED, Side.REQUEST, oldOp, "op", "",
                        null, "operation removed", opContext(oldOp), Map.of());
                continue;
            }
            diffOperation(oldOp, newOp);
        }
        assignIds();
        return findings;
    }

    private void diffOperation(OperationModel oldOp, OperationModel newOp) {
        diffParameters(oldOp, newOp);
        diffBody(oldOp, newOp);
        diffResponses(oldOp, newOp);
    }

    private void diffParameters(OperationModel oldOp, OperationModel newOp) {
        TreeMap<String, ParamModel> oldParams = indexParams(oldOp);
        TreeMap<String, ParamModel> newParams = indexParams(newOp);
        for (String key : union(oldParams.keySet(), newParams.keySet())) {
            ParamModel oldParam = oldParams.get(key);
            ParamModel newParam = newParams.get(key);
            int separator = key.indexOf(':');
            String subject = "parameters/" + key.substring(separator + 1);
            if (oldParam == null) {
                String code = newParam.isRequired()
                        ? ChangeCodes.PARAM_REQUIRED_ADDED : ChangeCodes.PARAM_OPTIONAL_ADDED;
                emit(code, Side.REQUEST, newOp, "parameters", subject, null,
                        "parameter " + newParam.getName() + " (" + newParam.getIn() + ") added",
                        Map.of(), paramContext(newParam));
                continue;
            }
            if (newParam == null) {
                emit(ChangeCodes.PARAM_REMOVED, Side.REQUEST, oldOp, "parameters", subject, null,
                        "parameter " + oldParam.getName() + " (" + oldParam.getIn() + ") removed",
                        paramContext(oldParam), Map.of());
                continue;
            }
            if (oldParam.isRequired() != newParam.isRequired()) {
                emit(newParam.isRequired() ? ChangeCodes.PARAM_BECAME_REQUIRED
                                : ChangeCodes.PARAM_BECAME_OPTIONAL,
                        Side.REQUEST, newOp, "parameters", subject, null,
                        "parameter " + newParam.getName() + " required flag changed",
                        Map.of("required", oldParam.isRequired()),
                        Map.of("required", newParam.isRequired()));
            }
            if (!Objects.equals(oldParam.getStyle(), newParam.getStyle())
                    && oldParam.getStyle() != null) {
                emit(ChangeCodes.PARAM_STYLE_CHANGED, Side.REQUEST, newOp, "parameters", subject,
                        null, "parameter " + newParam.getName() + " style changed",
                        Map.of("style", str(oldParam.getStyle())),
                        Map.of("style", str(newParam.getStyle())));
            }
            if (!oldParam.isDeprecated() && newParam.isDeprecated()) {
                emit(ChangeCodes.PARAM_DEPRECATED, Side.REQUEST, newOp, "parameters", subject,
                        null, "parameter " + newParam.getName() + " deprecated",
                        Map.of("deprecated", false), Map.of("deprecated", true));
            }
            if (oldParam.getSchema() != null && newParam.getSchema() != null) {
                diffSchema(oldParam.getSchema(), newParam.getSchema(), Side.REQUEST,
                        newOp, "parameters", subject + "/schema");
            }
        }
    }

    private void diffBody(OperationModel oldOp, OperationModel newOp) {
        BodyModel oldBody = oldOp.getRequestBody();
        BodyModel newBody = newOp.getRequestBody();
        if (oldBody == null && newBody != null) {
            emit(ChangeCodes.BODY_ADDED, Side.REQUEST, newOp, "requestBody", "", null,
                    "request body added", Map.of(),
                    Map.of("required", newBody.isRequired()));
            diffBodyMediaTypes(null, newBody, newOp);
            return;
        }
        if (oldBody != null && newBody == null) {
            emit(ChangeCodes.BODY_REMOVED, Side.REQUEST, oldOp, "requestBody", "", null,
                    "request body removed", Map.of("required", oldBody.isRequired()), Map.of());
            return;
        }
        if (oldBody == null) {
            return;
        }
        if (oldBody.isRequired() != newBody.isRequired()) {
            emit(newBody.isRequired() ? ChangeCodes.BODY_BECAME_REQUIRED
                            : ChangeCodes.BODY_BECAME_OPTIONAL, Side.REQUEST, newOp,
                    "requestBody", "", null, "request body required flag changed",
                    Map.of("required", oldBody.isRequired()),
                    Map.of("required", newBody.isRequired()));
        }
        diffBodyMediaTypes(oldBody, newBody, newOp);
    }

    private void diffBodyMediaTypes(BodyModel oldBody, BodyModel newBody, OperationModel newOp) {
        TreeMap<String, ESchema> oldMedia = oldBody == null ? new TreeMap<>() : oldBody.getMediaTypes();
        TreeMap<String, ESchema> newMedia = newBody == null ? new TreeMap<>() : newBody.getMediaTypes();
        for (String mediaType : union(oldMedia.keySet(), newMedia.keySet())) {
            String subject = "requestBody/content/" + mediaType;
            ESchema oldSchema = oldMedia.get(mediaType);
            ESchema newSchema = newMedia.get(mediaType);
            if (oldSchema == null) {
                emit(ChangeCodes.REQUEST_MEDIA_TYPE_ADDED, Side.REQUEST, newOp,
                        "requestBody", subject, null,
                        "request media type " + mediaType + " added",
                        Map.of(), Map.of("mediaType", mediaType));
                continue;
            }
            if (newSchema == null) {
                emit(ChangeCodes.REQUEST_MEDIA_TYPE_REMOVED, Side.REQUEST, newOp,
                        "requestBody", subject, null,
                        "request media type " + mediaType + " removed",
                        Map.of("mediaType", mediaType), Map.of());
                continue;
            }
            diffSchema(oldSchema, newSchema, Side.REQUEST, newOp, "requestBody", subject + "/schema");
        }
    }

    private void diffResponses(OperationModel oldOp, OperationModel newOp) {
        TreeMap<String, ResponseModel> oldResponses = oldOp.getResponses();
        TreeMap<String, ResponseModel> newResponses = newOp.getResponses();
        for (String status : union(oldResponses.keySet(), newResponses.keySet())) {
            ResponseModel oldResponse = oldResponses.get(status);
            ResponseModel newResponse = newResponses.get(status);
            String subject = "responses/" + status;
            if (oldResponse == null) {
                emit(ChangeCodes.STATUS_ADDED, Side.RESPONSE, newOp, "responses", subject, null,
                        "status code " + status + " added",
                        Map.of(), Map.of("status", status));
                continue;
            }
            if (newResponse == null) {
                String code = "default".equals(status)
                        ? ChangeCodes.DEFAULT_STATUS_REMOVED : ChangeCodes.STATUS_REMOVED;
                emit(code, Side.RESPONSE, oldOp, "responses", subject, null,
                        "status code " + status + " removed",
                        Map.of("status", status), Map.of());
                continue;
            }
            diffResponse(oldResponse, newResponse, newOp);
        }
    }

    private void diffResponse(ResponseModel oldResponse, ResponseModel newResponse,
                              OperationModel newOp) {
        String prefix = "responses/" + newResponse.getStatus();
        for (String headerName : union(oldResponse.getHeaders().keySet(),
                newResponse.getHeaders().keySet())) {
            ParamModel oldHeader = oldResponse.getHeaders().get(headerName);
            ParamModel newHeader = newResponse.getHeaders().get(headerName);
            String subject = prefix + "/headers/" + headerName;
            if (oldHeader == null || newHeader == null) {
                if (oldHeader != null) {
                    emit(ChangeCodes.RESPONSE_HEADER_REMOVED, Side.RESPONSE, newOp,
                            "responses", subject, null, "response header " + headerName + " removed",
                            Map.of("header", headerName), Map.of());
                }
                continue;
            }
            if (oldHeader.isRequired() && !newHeader.isRequired()) {
                emit(ChangeCodes.RESPONSE_HEADER_BECAME_OPTIONAL, Side.RESPONSE, newOp,
                        "responses", subject, null,
                        "response header " + headerName + " became optional",
                        Map.of("required", true), Map.of("required", false));
            }
        }
        for (String mediaType : union(oldResponse.getMediaTypes().keySet(),
                newResponse.getMediaTypes().keySet())) {
            String subject = prefix + "/content/" + mediaType;
            ESchema oldSchema = oldResponse.getMediaTypes().get(mediaType);
            ESchema newSchema = newResponse.getMediaTypes().get(mediaType);
            if (oldSchema == null) {
                emit(ChangeCodes.RESPONSE_MEDIA_TYPE_ADDED, Side.RESPONSE, newOp,
                        "responses", subject, null,
                        "response media type " + mediaType + " added",
                        Map.of(), Map.of("mediaType", mediaType));
                continue;
            }
            if (newSchema == null) {
                emit(ChangeCodes.RESPONSE_MEDIA_TYPE_REMOVED, Side.RESPONSE, newOp,
                        "responses", subject, null,
                        "response media type " + mediaType + " removed",
                        Map.of("mediaType", mediaType), Map.of());
                continue;
            }
            diffSchema(oldSchema, newSchema, Side.RESPONSE, newOp, "responses", subject + "/schema");
        }
    }

    private void diffSchema(ESchema oldSchema, ESchema newSchema, Side side,
                            OperationModel op, String anchor, String subject) {
        if (oldSchema == null || newSchema == null) {
            return;
        }
        if (oldSchema.hasBlockingMarker() || newSchema.hasBlockingMarker()) {
            emit(ChangeCodes.SCHEMA_UNRESOLVABLE, side, op, anchor, subject, null,
                    "schema structure could not be fully resolved",
                    markerContext(oldSchema), markerContext(newSchema));
            return;
        }
        if (!Objects.equals(str(oldSchema.getType()), str(newSchema.getType()))) {
            emit(ChangeCodes.TYPE_CHANGED, side, op, anchor, subject, null,
                    "type changed from " + str(oldSchema.getType()) + " to " + str(newSchema.getType()),
                    Map.of("type", str(oldSchema.getType())), Map.of("type", str(newSchema.getType())));
            return;
        }
        if (!Objects.equals(str(oldSchema.getFormat()), str(newSchema.getFormat()))) {
            emit(ChangeCodes.FORMAT_CHANGED, side, op, anchor, subject, null,
                    "format changed from " + str(oldSchema.getFormat())
                            + " to " + str(newSchema.getFormat()),
                    Map.of("format", str(oldSchema.getFormat())),
                    Map.of("format", str(newSchema.getFormat())));
        }
        diffEnums(oldSchema, newSchema, side, op, anchor, subject);
        if (oldSchema.isNullable() && !newSchema.isNullable()) {
            emit(ChangeCodes.NULLABILITY_TIGHTENED, side, op, anchor, subject, null,
                    "value no longer nullable",
                    Map.of("nullable", true), Map.of("nullable", false));
        }
        if (!oldSchema.isNullable() && newSchema.isNullable()) {
            emit(ChangeCodes.NULLABILITY_RELAXED, side, op, anchor, subject, null,
                    "value is newly nullable",
                    Map.of("nullable", false), Map.of("nullable", true));
        }
        if (!Objects.equals(oldSchema.getDefaultValue(), newSchema.getDefaultValue())) {
            String code = newSchema.getDefaultValue() == null
                    ? ChangeCodes.DEFAULT_REMOVED : ChangeCodes.DEFAULT_CHANGED;
            emit(code, side, op, anchor, subject, null, "default value changed",
                    Map.of("default", str(oldSchema.getDefaultValue())),
                    Map.of("default", str(newSchema.getDefaultValue())));
        }
        if (!Objects.equals(str(oldSchema.getPattern()), str(newSchema.getPattern()))
                && oldSchema.getPattern() != null) {
            emit(ChangeCodes.PATTERN_CHANGED, side, op, anchor, subject, null, "pattern changed",
                    Map.of("pattern", str(oldSchema.getPattern())),
                    Map.of("pattern", str(newSchema.getPattern())));
        }
        diffNumericBounds(oldSchema, newSchema, side, op, anchor, subject);
        diffLengthBounds(oldSchema, newSchema, side, op, anchor, subject);
        if (oldSchema.getAdditionalProperties() != null
                && oldSchema.getAdditionalProperties().getMarkers().containsKey("CLOSED")
                && (newSchema.getAdditionalProperties() == null
                || !newSchema.getAdditionalProperties().getMarkers().containsKey("CLOSED"))) {
            emit(ChangeCodes.ADDITIONAL_PROPERTIES_OPENED, side, op, anchor, subject, null,
                    "additionalProperties opened up",
                    Map.of("additionalProperties", false),
                    Map.of("additionalProperties", true));
        }
        if ((oldSchema.getAdditionalProperties() == null
                || !oldSchema.getAdditionalProperties().getMarkers().containsKey("CLOSED"))
                && newSchema.getAdditionalProperties() != null
                && newSchema.getAdditionalProperties().getMarkers().containsKey("CLOSED")) {
            emit(ChangeCodes.ADDITIONAL_PROPERTIES_CLOSED, side, op, anchor, subject, null,
                    "additionalProperties closed",
                    Map.of("additionalProperties", true),
                    Map.of("additionalProperties", false));
        }
        if (oldSchema.isReadOnly() != newSchema.isReadOnly()) {
            emit(ChangeCodes.READONLY_CHANGED, side, op, anchor, subject, null,
                    "readOnly flag changed",
                    Map.of("readOnly", oldSchema.isReadOnly()),
                    Map.of("readOnly", newSchema.isReadOnly()));
        }
        if (oldSchema.isWriteOnly() != newSchema.isWriteOnly()) {
            emit(ChangeCodes.WRITEONLY_CHANGED, side, op, anchor, subject, null,
                    "writeOnly flag changed",
                    Map.of("writeOnly", oldSchema.isWriteOnly()),
                    Map.of("writeOnly", newSchema.isWriteOnly()));
        }
        diffProperties(oldSchema, newSchema, side, op, anchor, subject);
        diffItems(oldSchema, newSchema, side, op, anchor, subject);
        diffBranches(oldSchema, newSchema, side, op, anchor, subject);
        diffDiscriminator(oldSchema, newSchema, side, op, anchor, subject);
    }

    private void diffEnums(ESchema oldSchema, ESchema newSchema, Side side,
                           OperationModel op, String anchor, String subject) {
        List<String> oldValues = oldSchema.getEnumValues();
        List<String> newValues = newSchema.getEnumValues();
        if (oldValues.isEmpty() && newValues.isEmpty()) {
            return;
        }
        Set<String> oldSet = new HashSet<>(oldValues);
        Set<String> newSet = new HashSet<>(newValues);
        List<String> removed = oldValues.stream().filter(value -> !newSet.contains(value))
                .sorted().toList();
        List<String> added = newValues.stream().filter(value -> !oldSet.contains(value))
                .sorted().toList();
        String code;
        if (!removed.isEmpty() && added.isEmpty()) {
            code = ChangeCodes.ENUM_NARROWED;
        } else if (removed.isEmpty() && !added.isEmpty()) {
            code = ChangeCodes.ENUM_WIDENED;
        } else {
            code = ChangeCodes.ENUM_CHANGED;
        }
        emit(code, side, op, anchor, subject, null,
                "enum values changed (removed=" + removed + ", added=" + added + ")",
                Map.of("values", oldValues), Map.of("values", newValues));
    }

    private void diffNumericBounds(ESchema oldSchema, ESchema newSchema, Side side,
                                   OperationModel op, String anchor, String subject) {
        if (!Objects.equals(oldSchema.getMinimum(), newSchema.getMinimum())
                && oldSchema.getMinimum() != null && newSchema.getMinimum() != null) {
            boolean raised = newSchema.getMinimum() > oldSchema.getMinimum();
            emit(raised ? ChangeCodes.MINIMUM_RAISED : ChangeCodes.MINIMUM_LOWERED, side, op,
                    anchor, subject, null,
                    "minimum " + (raised ? "raised" : "lowered"),
                    Map.of("minimum", oldSchema.getMinimum()),
                    Map.of("minimum", newSchema.getMinimum()));
        }
        if (!Objects.equals(oldSchema.getMaximum(), newSchema.getMaximum())
                && oldSchema.getMaximum() != null && newSchema.getMaximum() != null) {
            boolean lowered = newSchema.getMaximum() < oldSchema.getMaximum();
            emit(lowered ? ChangeCodes.MAXIMUM_LOWERED : ChangeCodes.MAXIMUM_RAISED, side, op,
                    anchor, subject, null,
                    "maximum " + (lowered ? "lowered" : "raised"),
                    Map.of("maximum", oldSchema.getMaximum()),
                    Map.of("maximum", newSchema.getMaximum()));
        }
    }

    private void diffLengthBounds(ESchema oldSchema, ESchema newSchema, Side side,
                                  OperationModel op, String anchor, String subject) {
        if (!Objects.equals(oldSchema.getMinLength(), newSchema.getMinLength())
                && oldSchema.getMinLength() != null && newSchema.getMinLength() != null) {
            boolean raised = newSchema.getMinLength() > oldSchema.getMinLength();
            emit(raised ? ChangeCodes.MIN_LENGTH_RAISED : ChangeCodes.MIN_LENGTH_LOWERED, side, op,
                    anchor, subject, null,
                    "minLength " + (raised ? "raised" : "lowered"),
                    Map.of("minLength", oldSchema.getMinLength()),
                    Map.of("minLength", newSchema.getMinLength()));
        }
        if (!Objects.equals(oldSchema.getMaxLength(), newSchema.getMaxLength())
                && oldSchema.getMaxLength() != null && newSchema.getMaxLength() != null) {
            boolean lowered = newSchema.getMaxLength() < oldSchema.getMaxLength();
            emit(lowered ? ChangeCodes.MAX_LENGTH_LOWERED : ChangeCodes.MAX_LENGTH_RAISED, side, op,
                    anchor, subject, null,
                    "maxLength " + (lowered ? "lowered" : "raised"),
                    Map.of("maxLength", oldSchema.getMaxLength()),
                    Map.of("maxLength", newSchema.getMaxLength()));
        }
    }

    private void diffProperties(ESchema oldSchema, ESchema newSchema, Side side,
                                OperationModel op, String anchor, String subject) {
        for (String name : union(oldSchema.getProperties().keySet(),
                newSchema.getProperties().keySet())) {
            ESchema oldProp = oldSchema.getProperties().get(name);
            ESchema newProp = newSchema.getProperties().get(name);
            String child = subject + "/properties/" + name;
            if (oldProp == null) {
                boolean required = newSchema.getRequired().containsKey(name);
                String code = required ? ChangeCodes.REQUIRED_FIELD_ADDED
                        : ChangeCodes.OPTIONAL_FIELD_ADDED;
                emit(code, side, op, anchor, child, null,
                        "field " + name + " added" + (required ? " (required)" : ""),
                        Map.of(), Map.of("required", required, "type", str(newProp.getType())));
                continue;
            }
            if (newProp == null) {
                emit(ChangeCodes.FIELD_REMOVED, side, op, anchor, child, null,
                        "field " + name + " removed",
                        Map.of("type", str(oldProp.getType())), Map.of());
                continue;
            }
            boolean oldRequired = oldSchema.getRequired().containsKey(name);
            boolean newRequired = newSchema.getRequired().containsKey(name);
            if (oldRequired != newRequired) {
                emit(newRequired ? ChangeCodes.FIELD_BECAME_REQUIRED
                                : ChangeCodes.FIELD_BECAME_OPTIONAL, side, op, anchor, child,
                        null, "field " + name + " required flag changed",
                        Map.of("required", oldRequired), Map.of("required", newRequired));
            }
            diffSchema(oldProp, newProp, side, op, anchor, child);
        }
    }

    private void diffItems(ESchema oldSchema, ESchema newSchema, Side side,
                           OperationModel op, String anchor, String subject) {
        if (oldSchema.getItems() != null && newSchema.getItems() != null) {
            diffSchema(oldSchema.getItems(), newSchema.getItems(), side, op, anchor,
                    subject + "/items");
        }
    }

    private void diffBranches(ESchema oldSchema, ESchema newSchema, Side side,
                              OperationModel op, String anchor, String subject) {
        List<ESchema> oldBranches = oldSchema.getOneOf().isEmpty()
                ? oldSchema.getAnyOf() : oldSchema.getOneOf();
        List<ESchema> newBranches = newSchema.getOneOf().isEmpty()
                ? newSchema.getAnyOf() : newSchema.getOneOf();
        if (oldBranches.isEmpty() && newBranches.isEmpty()) {
            return;
        }
        List<String> oldSignatures = oldBranches.stream().map(ESchema::structuralSignature)
                .sorted().toList();
        List<String> newSignatures = newBranches.stream().map(ESchema::structuralSignature)
                .sorted().toList();
        int removed = 0;
        int added = 0;
        for (String signature : oldSignatures) {
            if (!newSignatures.contains(signature)) {
                removed++;
            }
        }
        for (String signature : newSignatures) {
            if (!oldSignatures.contains(signature)) {
                added++;
            }
        }
        if (removed > 0) {
            emit(ChangeCodes.COMPOSITION_BRANCH_REMOVED, side, op, anchor, subject, null,
                    removed + " composition branch(es) removed",
                    Map.of("branches", oldSignatures.size()),
                    Map.of("branches", newSignatures.size()));
        }
        if (added > 0) {
            emit(ChangeCodes.COMPOSITION_BRANCH_ADDED, side, op, anchor, subject, null,
                    added + " composition branch(es) added",
                    Map.of("branches", oldSignatures.size()),
                    Map.of("branches", newSignatures.size()));
        }
    }

    private void diffDiscriminator(ESchema oldSchema, ESchema newSchema, Side side,
                                   OperationModel op, String anchor, String subject) {
        if (!Objects.equals(oldSchema.getDiscriminatorProperty(),
                newSchema.getDiscriminatorProperty())) {
            emit(ChangeCodes.DISCRIMINATOR_CHANGED, side, op, anchor, subject, null,
                    "discriminator property changed",
                    Map.of("propertyName", str(oldSchema.getDiscriminatorProperty())),
                    Map.of("propertyName", str(newSchema.getDiscriminatorProperty())));
        }
        Set<String> oldMappings = oldSchema.getDiscriminatorMapping().keySet();
        Set<String> newMappings = newSchema.getDiscriminatorMapping().keySet();
        List<String> removed = oldMappings.stream().filter(value -> !newMappings.contains(value))
                .sorted().toList();
        List<String> added = newMappings.stream().filter(value -> !oldMappings.contains(value))
                .sorted().toList();
        if (!removed.isEmpty()) {
            emit(ChangeCodes.DISCRIMINATOR_MAPPING_REMOVED, side, op, anchor, subject, null,
                    "discriminator mapping values removed: " + removed,
                    Map.of("values", removed), Map.of());
        }
        if (!added.isEmpty()) {
            emit(ChangeCodes.DISCRIMINATOR_MAPPING_ADDED, side, op, anchor, subject, null,
                    "discriminator mapping values added: " + added,
                    Map.of(), Map.of("values", added));
        }
    }

    private void emit(String code, Side side, OperationModel op, String anchor,
                      String subject, String locator, String summary,
                      Map<String, Object> before, Map<String, Object> after) {
        Finding finding = new Finding();
        finding.setCode(code);
        finding.setSeverity(policy.severity(code, side).name());
        finding.setSide(side.name());
        finding.setMethod(op.getMethod());
        finding.setPath(op.getPath());
        finding.setAnchor(anchor);
        finding.setSubject(subject == null ? "" : subject);
        finding.setLocator(buildLocator(op, anchor, subject));
        finding.setSummary(summary);
        finding.setBefore(new LinkedHashMap<>(before));
        finding.setAfter(new LinkedHashMap<>(after));
        findings.add(finding);
    }

    /** Stable string an exemption can bind to and re-resolve against a later candidate. */
    static String buildLocator(OperationModel op, String anchor, String subject) {
        StringBuilder locator = new StringBuilder();
        locator.append(op.getMethod()).append(' ').append(op.getPath())
                .append('#').append(anchor);
        if (subject != null && !subject.isEmpty()) {
            locator.append('/').append(subject);
        }
        return locator.toString();
    }

    private void assignIds() {
        findings.sort(java.util.Comparator
                .comparing(Finding::getMethod)
                .thenComparing(Finding::getPath)
                .thenComparing(Finding::getLocator)
                .thenComparing(Finding::getCode)
                .thenComparing(finding -> Canonicalizer.canonical(
                        mapNode(finding.getBefore())))
                .thenComparing(finding -> Canonicalizer.canonical(
                        mapNode(finding.getAfter()))));
        int index = 1;
        for (Finding finding : findings) {
            finding.setId(String.format("F%04d", index++));
        }
    }

    private com.fasterxml.jackson.databind.JsonNode mapNode(Map<String, Object> map) {
        return new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(map);
    }

    private TreeMap<String, ParamModel> indexParams(OperationModel op) {
        TreeMap<String, ParamModel> indexed = new TreeMap<>();
        for (ParamModel parameter : op.getParameters()) {
            indexed.put(parameter.getIn() + ":" + parameter.getName(), parameter);
        }
        return indexed;
    }

    private Set<String> union(Set<String> left, Set<String> right) {
        Set<String> merged = new java.util.TreeSet<>(left);
        merged.addAll(right);
        return merged;
    }

    private Map<String, Object> opContext(OperationModel op) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("method", op.getMethod());
        context.put("path", op.getPath());
        return context;
    }

    private Map<String, Object> paramContext(ParamModel parameter) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("name", parameter.getName());
        context.put("in", parameter.getIn());
        context.put("required", parameter.isRequired());
        return context;
    }

    private Map<String, Object> markerContext(ESchema schema) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (schema != null) {
            context.put("markers", schema.getMarkers());
            if (schema.getRef() != null) {
                context.put("$ref", schema.getRef());
            }
        }
        return context;
    }

    private String str(String value) {
        return value;
    }

    /** Union of operation keys in deterministic path+method order. */
    private static final class TreeSetOfKeys extends java.util.TreeSet<String> {
        TreeSetOfKeys(SpecModel baseline, SpecModel candidate) {
            addAll(baseline.getOperations().keySet());
            addAll(candidate.getOperations().keySet());
        }
    }
}
