package com.example.compat.engine;

/**
 * Catalog of structural change codes. Every code has a fixed compatibility
 * meaning; the policy only maps (code, side) to a severity, which is why two
 * policy versions can disagree without touching the diff engine.
 */
public final class ChangeCodes {
    private ChangeCodes() {
    }

    public static final String OP_REMOVED = "OP_REMOVED";
    public static final String OP_ADDED = "OP_ADDED";

    public static final String PARAM_REQUIRED_ADDED = "PARAM_REQUIRED_ADDED";
    public static final String PARAM_OPTIONAL_ADDED = "PARAM_OPTIONAL_ADDED";
    public static final String PARAM_REMOVED = "PARAM_REMOVED";
    public static final String PARAM_BECAME_REQUIRED = "PARAM_BECAME_REQUIRED";
    public static final String PARAM_BECAME_OPTIONAL = "PARAM_BECAME_OPTIONAL";
    public static final String PARAM_DEPRECATED = "PARAM_DEPRECATED";
    public static final String PARAM_STYLE_CHANGED = "PARAM_STYLE_CHANGED";

    public static final String BODY_BECAME_REQUIRED = "BODY_BECAME_REQUIRED";
    public static final String BODY_BECAME_OPTIONAL = "BODY_BECAME_OPTIONAL";
    public static final String BODY_REMOVED = "BODY_REMOVED";
    public static final String BODY_ADDED = "BODY_ADDED";

    public static final String REQUEST_MEDIA_TYPE_REMOVED = "REQUEST_MEDIA_TYPE_REMOVED";
    public static final String REQUEST_MEDIA_TYPE_ADDED = "REQUEST_MEDIA_TYPE_ADDED";
    public static final String RESPONSE_MEDIA_TYPE_REMOVED = "RESPONSE_MEDIA_TYPE_REMOVED";
    public static final String RESPONSE_MEDIA_TYPE_ADDED = "RESPONSE_MEDIA_TYPE_ADDED";

    public static final String STATUS_REMOVED = "STATUS_REMOVED";
    public static final String STATUS_ADDED = "STATUS_ADDED";
    public static final String DEFAULT_STATUS_REMOVED = "DEFAULT_STATUS_REMOVED";
    public static final String RESPONSE_HEADER_REMOVED = "RESPONSE_HEADER_REMOVED";
    public static final String RESPONSE_HEADER_BECAME_OPTIONAL = "RESPONSE_HEADER_BECAME_OPTIONAL";

    public static final String TYPE_CHANGED = "TYPE_CHANGED";
    public static final String ENUM_NARROWED = "ENUM_NARROWED";
    public static final String ENUM_WIDENED = "ENUM_WIDENED";
    public static final String ENUM_CHANGED = "ENUM_CHANGED";
    public static final String NULLABILITY_TIGHTENED = "NULLABILITY_TIGHTENED";
    public static final String NULLABILITY_RELAXED = "NULLABILITY_RELAXED";
    public static final String REQUIRED_FIELD_ADDED = "REQUIRED_FIELD_ADDED";
    public static final String OPTIONAL_FIELD_ADDED = "OPTIONAL_FIELD_ADDED";
    public static final String FIELD_REMOVED = "FIELD_REMOVED";
    public static final String FIELD_BECAME_REQUIRED = "FIELD_BECAME_REQUIRED";
    public static final String FIELD_BECAME_OPTIONAL = "FIELD_BECAME_OPTIONAL";
    public static final String MINIMUM_RAISED = "MINIMUM_RAISED";
    public static final String MINIMUM_LOWERED = "MINIMUM_LOWERED";
    public static final String MAXIMUM_LOWERED = "MAXIMUM_LOWERED";
    public static final String MAXIMUM_RAISED = "MAXIMUM_RAISED";
    public static final String MIN_LENGTH_RAISED = "MIN_LENGTH_RAISED";
    public static final String MIN_LENGTH_LOWERED = "MIN_LENGTH_LOWERED";
    public static final String MAX_LENGTH_LOWERED = "MAX_LENGTH_LOWERED";
    public static final String MAX_LENGTH_RAISED = "MAX_LENGTH_RAISED";
    public static final String DEFAULT_CHANGED = "DEFAULT_CHANGED";
    public static final String DEFAULT_REMOVED = "DEFAULT_REMOVED";
    public static final String FORMAT_CHANGED = "FORMAT_CHANGED";
    public static final String PATTERN_CHANGED = "PATTERN_CHANGED";
    public static final String ITEMS_CHANGED = "ITEMS_CHANGED";
    public static final String COMPOSITION_BRANCH_REMOVED = "COMPOSITION_BRANCH_REMOVED";
    public static final String COMPOSITION_BRANCH_ADDED = "COMPOSITION_BRANCH_ADDED";
    public static final String DISCRIMINATOR_CHANGED = "DISCRIMINATOR_CHANGED";
    public static final String DISCRIMINATOR_MAPPING_REMOVED = "DISCRIMINATOR_MAPPING_REMOVED";
    public static final String DISCRIMINATOR_MAPPING_ADDED = "DISCRIMINATOR_MAPPING_ADDED";
    public static final String ADDITIONAL_PROPERTIES_CLOSED = "ADDITIONAL_PROPERTIES_CLOSED";
    public static final String ADDITIONAL_PROPERTIES_OPENED = "ADDITIONAL_PROPERTIES_OPENED";
    public static final String READONLY_CHANGED = "READONLY_CHANGED";
    public static final String WRITEONLY_CHANGED = "WRITEONLY_CHANGED";
    public static final String SCHEMA_UNRESOLVABLE = "SCHEMA_UNRESOLVABLE";
}
