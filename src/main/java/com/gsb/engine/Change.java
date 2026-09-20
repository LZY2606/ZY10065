package com.gsb.engine;

public record Change(
        String code,
        String pointer,
        String severity,
        String direction,
        String detail,
        Object oldValue,
        Object newValue
) {
    public static final String BREAKING = "BREAKING";
    public static final String WARNING = "WARNING";
    public static final String COMPATIBLE = "COMPATIBLE";

    public static final String REQUEST = "REQUEST";
    public static final String RESPONSE = "RESPONSE";
    public static final String OPERATION = "OPERATION";

    public String locator(String method, String path) {
        return method + " " + path + " " + code + " " + pointer;
    }
}
