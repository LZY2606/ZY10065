package com.example.compat.engine;

public final class ParamModel {
    private final String name;
    private final String in;
    private final boolean required;
    private final boolean deprecated;
    private final String style;
    private final ESchema schema;

    public ParamModel(String name, String in, boolean required, boolean deprecated,
                      String style, ESchema schema) {
        this.name = name;
        this.in = in;
        this.required = required;
        this.deprecated = deprecated;
        this.style = style;
        this.schema = schema;
    }

    public String getName() { return name; }
    public String getIn() { return in; }
    public boolean isRequired() { return required; }
    public boolean isDeprecated() { return deprecated; }
    public String getStyle() { return style; }
    public ESchema getSchema() { return schema; }
}
