package com.example.compat.engine;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Flattened, reference-resolved effective schema. Produced deterministically
 * (all maps are sorted) so two documents compare byte-stably.
 *
 * markers records structural evidence that must be shown but must NOT be
 * counted in the pass-rate: UNRESOLVABLE_REF and CYCLE.
 */
public final class ESchema {
    private String type;
    private String format;
    private String description;
    private TreeMap<String, ESchema> properties = new TreeMap<>();
    private TreeMap<String, String> required = new TreeMap<>();
    private ESchema items;
    private ESchema additionalProperties;
    private List<ESchema> oneOf = List.of();
    private List<ESchema> anyOf = List.of();
    private List<String> enumValues = List.of();
    private String discriminatorProperty;
    private TreeMap<String, String> discriminatorMapping = new TreeMap<>();
    private boolean nullable;
    private boolean readOnly;
    private boolean writeOnly;
    private boolean deprecated;
    private String defaultValue;
    private Double minimum;
    private Double maximum;
    private Boolean exclusiveMinimum;
    private Boolean exclusiveMaximum;
    private Integer minLength;
    private Integer maxLength;
    private String pattern;
    private Integer minItems;
    private Integer maxItems;
    private boolean uniqueItems;
    private TreeMap<String, String> markers = new TreeMap<>();
    private String ref;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public TreeMap<String, ESchema> getProperties() { return properties; }
    public void setProperties(TreeMap<String, ESchema> properties) { this.properties = properties; }
    public TreeMap<String, String> getRequired() { return required; }
    public void setRequired(TreeMap<String, String> required) { this.required = required; }
    public ESchema getItems() { return items; }
    public void setItems(ESchema items) { this.items = items; }
    public ESchema getAdditionalProperties() { return additionalProperties; }
    public void setAdditionalProperties(ESchema additionalProperties) { this.additionalProperties = additionalProperties; }
    public List<ESchema> getOneOf() { return oneOf; }
    public void setOneOf(List<ESchema> oneOf) { this.oneOf = oneOf == null ? List.of() : oneOf; }
    public List<ESchema> getAnyOf() { return anyOf; }
    public void setAnyOf(List<ESchema> anyOf) { this.anyOf = anyOf == null ? List.of() : anyOf; }
    public List<String> getEnumValues() { return enumValues; }
    public void setEnumValues(List<String> enumValues) { this.enumValues = enumValues == null ? List.of() : enumValues; }
    public String getDiscriminatorProperty() { return discriminatorProperty; }
    public void setDiscriminatorProperty(String discriminatorProperty) { this.discriminatorProperty = discriminatorProperty; }
    public TreeMap<String, String> getDiscriminatorMapping() { return discriminatorMapping; }
    public void setDiscriminatorMapping(TreeMap<String, String> discriminatorMapping) { this.discriminatorMapping = discriminatorMapping; }
    public boolean isNullable() { return nullable; }
    public void setNullable(boolean nullable) { this.nullable = nullable; }
    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
    public boolean isWriteOnly() { return writeOnly; }
    public void setWriteOnly(boolean writeOnly) { this.writeOnly = writeOnly; }
    public boolean isDeprecated() { return deprecated; }
    public void setDeprecated(boolean deprecated) { this.deprecated = deprecated; }
    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
    public Double getMinimum() { return minimum; }
    public void setMinimum(Double minimum) { this.minimum = minimum; }
    public Double getMaximum() { return maximum; }
    public void setMaximum(Double maximum) { this.maximum = maximum; }
    public Boolean getExclusiveMinimum() { return exclusiveMinimum; }
    public void setExclusiveMinimum(Boolean exclusiveMinimum) { this.exclusiveMinimum = exclusiveMinimum; }
    public Boolean getExclusiveMaximum() { return exclusiveMaximum; }
    public void setExclusiveMaximum(Boolean exclusiveMaximum) { this.exclusiveMaximum = exclusiveMaximum; }
    public Integer getMinLength() { return minLength; }
    public void setMinLength(Integer minLength) { this.minLength = minLength; }
    public Integer getMaxLength() { return maxLength; }
    public void setMaxLength(Integer maxLength) { this.maxLength = maxLength; }
    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }
    public Integer getMinItems() { return minItems; }
    public void setMinItems(Integer minItems) { this.minItems = minItems; }
    public Integer getMaxItems() { return maxItems; }
    public void setMaxItems(Integer maxItems) { this.maxItems = maxItems; }
    public boolean isUniqueItems() { return uniqueItems; }
    public void setUniqueItems(boolean uniqueItems) { this.uniqueItems = uniqueItems; }
    public TreeMap<String, String> getMarkers() { return markers; }
    public void setMarkers(TreeMap<String, String> markers) { this.markers = markers; }
    public String getRef() { return ref; }
    public void setRef(String ref) { this.ref = ref; }

    public boolean hasBlockingMarker() {
        return markers.containsKey("UNRESOLVABLE_REF") || markers.containsKey("CYCLE");
    }

    /** Stable structural fingerprint for matching composition branches across versions. */
    public String structuralSignature() {
        StringBuilder sb = new StringBuilder();
        appendSignature(sb);
        return Canonicalizer.sha256(sb.toString());
    }

    private void appendSignature(StringBuilder sb) {
        sb.append(type == null ? "?" : type).append('|');
        sb.append(enumValues.isEmpty() ? "" : "E" + String.join(",", enumValues)).append('|');
        for (Map.Entry<String, ESchema> e : properties.entrySet()) {
            sb.append('p').append(e.getKey()).append(':');
            e.getValue().appendSignature(sb);
        }
        if (items != null) {
            sb.append("i:");
            items.appendSignature(sb);
        }
        for (ESchema branch : oneOf) {
            sb.append('1');
            branch.appendSignature(sb);
        }
        for (ESchema branch : anyOf) {
            sb.append('a');
            branch.appendSignature(sb);
        }
    }
}
