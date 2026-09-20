package com.gsb.web;

import com.gsb.service.ExemptionService;
import com.gsb.service.RunService;

/** Request DTOs shared by controllers. */
public final class Dtos {

    private Dtos() {
    }

    public record RawTextBody(String rawText) {
    }

    public record RunBody(String baselineKey, String candidateKey,
                          String policyVersion, String sampleSet) {
        RunService.RunRequest toRequest() {
            return new RunService.RunRequest(baselineKey, candidateKey,
                    policyVersion, sampleSet);
        }
    }

    public record GrantBody(String candidateKey, String method, String path,
                            String changeCode, String pointer, String reason,
                            String operator, java.time.Instant validFrom,
                            java.time.Instant validUntil, Integer validDays) {
        ExemptionService.GrantRequest toRequest() {
            return new ExemptionService.GrantRequest(candidateKey, method, path,
                    changeCode, pointer, reason, operator, validFrom, validUntil,
                    validDays);
        }
    }

    public record RevokeBody(String operator, String reason) {
        ExemptionService.RevokeRequest toRequest() {
            return new ExemptionService.RevokeRequest(operator, reason);
        }
    }
}
