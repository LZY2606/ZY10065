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
@Table(name = "decision_event")
public class DecisionEvent {

    public static final String EXEMPTION_GRANTED = "EXEMPTION_GRANTED";
    public static final String EXEMPTION_REVOKED = "EXEMPTION_REVOKED";
    public static final String TRIAGE = "TRIAGE";
    public static final String UNDO = "UNDO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String eventKey;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String operator;

    @Lob
    @Column(nullable = false)
    private String reason;

    @Lob
    @Column(nullable = false)
    private String payloadJson;

    private Long relatedExemptionId;

    private String fromVersion;

    private String toVersion;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getEventKey() {
        return eventKey;
    }

    public void setEventKey(String eventKey) {
        this.eventKey = eventKey;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public Long getRelatedExemptionId() {
        return relatedExemptionId;
    }

    public void setRelatedExemptionId(Long relatedExemptionId) {
        this.relatedExemptionId = relatedExemptionId;
    }

    public String getFromVersion() {
        return fromVersion;
    }

    public void setFromVersion(String fromVersion) {
        this.fromVersion = fromVersion;
    }

    public String getToVersion() {
        return toVersion;
    }

    public void setToVersion(String toVersion) {
        this.toVersion = toVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
