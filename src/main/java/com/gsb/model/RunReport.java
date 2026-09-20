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
@Table(name = "run_report")
public class RunReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String runKey;

    @Column(nullable = false)
    private String baselineHash;

    @Column(nullable = false)
    private String candidateKey;

    @Column(nullable = false)
    private String candidateHash;

    @Column(nullable = false)
    private String policyVersion;

    @Column(nullable = false)
    private String sampleSet;

    @Column(nullable = false)
    private String sampleSetHash;

    @Column(nullable = false)
    private String status;

    @Lob
    @Column(nullable = false)
    private String reportJson;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getRunKey() {
        return runKey;
    }

    public void setRunKey(String runKey) {
        this.runKey = runKey;
    }

    public String getBaselineHash() {
        return baselineHash;
    }

    public void setBaselineHash(String baselineHash) {
        this.baselineHash = baselineHash;
    }

    public String getCandidateKey() {
        return candidateKey;
    }

    public void setCandidateKey(String candidateKey) {
        this.candidateKey = candidateKey;
    }

    public String getCandidateHash() {
        return candidateHash;
    }

    public void setCandidateHash(String candidateHash) {
        this.candidateHash = candidateHash;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }

    public String getSampleSet() {
        return sampleSet;
    }

    public void setSampleSet(String sampleSet) {
        this.sampleSet = sampleSet;
    }

    public String getSampleSetHash() {
        return sampleSetHash;
    }

    public void setSampleSetHash(String sampleSetHash) {
        this.sampleSetHash = sampleSetHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReportJson() {
        return reportJson;
    }

    public void setReportJson(String reportJson) {
        this.reportJson = reportJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
