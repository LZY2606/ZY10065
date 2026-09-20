package com.example.compat.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One structural change at a stable locator. locator is anchor + JSON-pointer-ish
 * subject so an exemption can be bound to exactly this place in the candidate.
 */
public final class Finding {
    private String id;
    private String code;
    private String severity;
    private String side;
    private String method;
    private String path;
    private String anchor;
    private String subject;
    private String locator;
    private String summary;
    private Map<String, Object> before = new LinkedHashMap<>();
    private Map<String, Object> after = new LinkedHashMap<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getAnchor() { return anchor; }
    public void setAnchor(String anchor) { this.anchor = anchor; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getLocator() { return locator; }
    public void setLocator(String locator) { this.locator = locator; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public Map<String, Object> getBefore() { return before; }
    public void setBefore(Map<String, Object> before) { this.before = before; }
    public Map<String, Object> getAfter() { return after; }
    public void setAfter(Map<String, Object> after) { this.after = after; }
}
