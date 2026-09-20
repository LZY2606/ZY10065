package com.gsb.engine;

public record ParamInfo(String name, String in, boolean required, String style, SchemaNode schema) {
    public String locator() {
        return in + ":" + name;
    }
}
