package com.example.compat.engine;

import java.util.Map;

/** compat-1.0: strict baseline rules; default and format changes are NON_BREAKING. */
public class PolicyV1 implements CompatibilityPolicy {

    static final Map<String, Severity> RULES = Map.ofEntries(
            Map.entry(ChangeCodes.OP_REMOVED, Severity.BREAKING),
            Map.entry(ChangeCodes.OP_ADDED, Severity.NON_BREAKING),
            Map.entry(ChangeCodes.PARAM_REQUIRED_ADDED, Severity.BREAKING),
            Map.entry(ChangeCodes.PARAM_OPTIONAL_ADDED, Severity.NON_BREAKING),
            Map.entry(ChangeCodes.PARAM_REMOVED, Severity.BREAKING),
            Map.entry(ChangeCodes.PARAM_BECAME_REQUIRED, Severity.BREAKING),
            Map.entry(ChangeCodes.PARAM_BECAME_OPTIONAL, Severity.NON_BREAKING),
            Map.entry(ChangeCodes.PARAM_DEPRECATED, Severity.INFO),
            Map.entry(ChangeCodes.PARAM_STYLE_CHANGED, Severity.NON_BREAKING),
            Map.entry(ChangeCodes.BODY_BECAME_REQUIRED, Severity.BREAKING),
            Map.entry(ChangeCodes.BODY_BECAME_OPTIONAL, Severity.NON_BREAKING),
            Map.entry(ChangeCodes.BODY_REMOVED, Severity.BREAKING),
            Map.entry(ChangeCodes.BODY_ADDED, Severity.NON_BREAKING),
            Map.entry(ChangeCodes.STATUS_REMOVED, Severity.BREAKING),
            Map.entry(ChangeCodes.STATUS_ADDED, Severity.NON_BREAKING),
            Map.entry(ChangeCodes.DEFAULT_STATUS_REMOVED, Severity.NON_BREAKING),
            Map.entry(ChangeCodes.RESPONSE_HEADER_REMOVED, Severity.NON_BREAKING),
            Map.entry(ChangeCodes.RESPONSE_HEADER_BECAME_OPTIONAL, Severity.BREAKING),
            Map.entry(ChangeCodes.TYPE_CHANGED, Severity.BREAKING),
            Map.entry(ChangeCodes.ENUM_CHANGED, Severity.BREAKING),
            Map.entry(ChangeCodes.NULLABILITY_TIGHTENED, Severity.BREAKING),
            Map.entry(ChangeCodes.NULLABILITY_RELAXED, Severity.BREAKING),
            Map.entry(ChangeCodes.FORMAT_CHANGED, Severity.NON_BREAKING),
            Map.entry(ChangeCodes.PATTERN_CHANGED, Severity.NON_BREAKING),
            Map.entry(ChangeCodes.ITEMS_CHANGED, Severity.BREAKING),
            Map.entry(ChangeCodes.COMPOSITION_BRANCH_REMOVED, Severity.BREAKING),
            Map.entry(ChangeCodes.COMPOSITION_BRANCH_ADDED, Severity.NON_BREAKING),
            Map.entry(ChangeCodes.DISCRIMINATOR_CHANGED, Severity.BREAKING),
            Map.entry(ChangeCodes.DISCRIMINATOR_MAPPING_REMOVED, Severity.BREAKING),
            Map.entry(ChangeCodes.DISCRIMINATOR_MAPPING_ADDED, Severity.NON_BREAKING),
            Map.entry(ChangeCodes.ADDITIONAL_PROPERTIES_CLOSED, Severity.BREAKING),
            Map.entry(ChangeCodes.ADDITIONAL_PROPERTIES_OPENED, Severity.NON_BREAKING),
            Map.entry(ChangeCodes.READONLY_CHANGED, Severity.NON_BREAKING),
            Map.entry(ChangeCodes.WRITEONLY_CHANGED, Severity.NON_BREAKING),
            Map.entry(ChangeCodes.SCHEMA_UNRESOLVABLE, Severity.EVIDENCE)
    );

    @Override
    public String version() {
        return "compat-1.0";
    }

    @Override
    public Severity severity(String code, Side side) {
        if (RULES.containsKey(code)) {
            return RULES.get(code);
        }
        return sideAware(code, side);
    }

    static Severity sideAware(String code, Side side) {
        boolean request = side == Side.REQUEST;
        boolean breaking = switch (code) {
            case ChangeCodes.REQUEST_MEDIA_TYPE_REMOVED, ChangeCodes.RESPONSE_MEDIA_TYPE_REMOVED -> true;
            case ChangeCodes.REQUEST_MEDIA_TYPE_ADDED, ChangeCodes.RESPONSE_MEDIA_TYPE_ADDED -> false;
            case ChangeCodes.ENUM_NARROWED -> request;
            case ChangeCodes.ENUM_WIDENED -> !request;
            case ChangeCodes.REQUIRED_FIELD_ADDED -> request;
            case ChangeCodes.OPTIONAL_FIELD_ADDED -> false;
            case ChangeCodes.FIELD_REMOVED -> !request;
            case ChangeCodes.FIELD_BECAME_REQUIRED -> request;
            case ChangeCodes.FIELD_BECAME_OPTIONAL -> !request;
            case ChangeCodes.MINIMUM_RAISED, ChangeCodes.MAXIMUM_LOWERED,
                 ChangeCodes.MIN_LENGTH_RAISED, ChangeCodes.MAX_LENGTH_LOWERED -> request;
            case ChangeCodes.MINIMUM_LOWERED, ChangeCodes.MAXIMUM_RAISED,
                 ChangeCodes.MIN_LENGTH_LOWERED, ChangeCodes.MAX_LENGTH_RAISED -> !request;
            case ChangeCodes.DEFAULT_CHANGED, ChangeCodes.DEFAULT_REMOVED -> false;
            default -> false;
        };
        return breaking ? Severity.BREAKING : Severity.NON_BREAKING;
    }
}
