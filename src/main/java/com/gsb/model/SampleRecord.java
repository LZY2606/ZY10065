package com.gsb.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "sample_record")
public class SampleRecord {

    public static final String COMPLETE = "COMPLETE";
    public static final String INCOMPLETE_REQUEST = "INCOMPLETE_REQUEST";
    public static final String INCOMPLETE_RESPONSE = "INCOMPLETE_RESPONSE";
    public static final String EMPTY = "EMPTY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sampleSet;

    @Column(nullable = false, unique = true)
    private String dedupKey;

    @Column(nullable = false)
    private String method;

    @Column(nullable = false)
    private String path;

    private String requestMediaType;

    @Lob
    private String requestBody;

    private Integer statusCode;

    private String responseMediaType;

    @Lob
    private String responseBody;

    @Column(nullable = false)
    private String completeness;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getSampleSet() {
        return sampleSet;
    }

    public void setSampleSet(String sampleSet) {
        this.sampleSet = sampleSet;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getRequestMediaType() {
        return requestMediaType;
    }

    public void setRequestMediaType(String requestMediaType) {
        this.requestMediaType = requestMediaType;
    }

    public String getRequestBody() {
        return requestBody;
    }

    public void setRequestBody(String requestBody) {
        this.requestBody = requestBody;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
    }

    public String getResponseMediaType() {
        return responseMediaType;
    }

    public void setResponseMediaType(String responseMediaType) {
        this.responseMediaType = responseMediaType;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public String getCompleteness() {
        return completeness;
    }

    public void setCompleteness(String completeness) {
        this.completeness = completeness;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
