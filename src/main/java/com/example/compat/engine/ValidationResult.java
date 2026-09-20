package com.example.compat.engine;

import java.util.ArrayList;
import java.util.List;

/** Result of validating one sample body/parameter set against one spec version. */
public final class ValidationResult {
    private boolean complete = true;
    private boolean valid = true;
    private final List<String> issues = new ArrayList<>();
    private final List<Evidence> evidences = new ArrayList<>();

    public boolean isComplete() { return complete; }
    public void setComplete(boolean complete) { this.complete = complete; }
    public boolean isValid() { return valid; }
    public void setValid(boolean valid) { this.valid = valid; }
    public List<String> getIssues() { return issues; }
    public List<Evidence> getEvidences() { return evidences; }

    public void addIssue(String issue) {
        valid = false;
        issues.add(issue);
    }

    public void addEvidence(String kind, String detail) {
        evidences.add(new Evidence(kind, detail));
    }

    public boolean hasStructuralEvidence() {
        return evidences.stream().anyMatch(evidence ->
                StructuralEvidence.UNRESOLVABLE_REF.equals(evidence.kind())
                        || StructuralEvidence.CYCLE.equals(evidence.kind()));
    }

    public record Evidence(String kind, String detail) {
    }
}
