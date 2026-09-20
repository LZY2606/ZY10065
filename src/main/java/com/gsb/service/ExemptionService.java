package com.gsb.service;

import com.gsb.model.DecisionEvent;
import com.gsb.model.DecisionEventRepository;
import com.gsb.model.Exemption;
import com.gsb.model.ExemptionRepository;
import com.gsb.model.SpecDocument;
import com.gsb.model.SpecDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Time-limited exemptions bound to an exact operation/change locator and to the
 * exact candidate content hash at grant time. Every human decision appends an
 * immutable DecisionEvent; undo is a new event, never a deleted row.
 */
@Service
public class ExemptionService {

    private final ExemptionRepository exemptions;
    private final DecisionEventRepository events;
    private final SpecDocumentRepository specs;

    public ExemptionService(ExemptionRepository exemptions,
                            DecisionEventRepository events,
                            SpecDocumentRepository specs) {
        this.exemptions = exemptions;
        this.events = events;
        this.specs = specs;
    }

    public record GrantRequest(String candidateKey, String method, String path,
                               String changeCode, String pointer, String reason,
                               String operator, Instant validFrom, Instant validUntil,
                               Integer validDays) {
    }

    public record RevokeRequest(String operator, String reason) {
    }

    @Transactional
    public Exemption grant(GrantRequest req) {
        requireText(req.candidateKey(), "candidateKey");
        requireText(req.method(), "method");
        requireText(req.path(), "path");
        requireText(req.changeCode(), "changeCode");
        requireText(req.reason(), "reason");
        requireText(req.operator(), "operator");

        SpecDocument candidate = specs.findBySpecKey(req.candidateKey())
                .orElseThrow(() -> new NotFoundException(
                        "candidate spec not found: " + req.candidateKey()));

        Instant from = req.validFrom() == null ? Instant.now() : req.validFrom();
        Instant until;
        if (req.validUntil() != null) {
            until = req.validUntil();
        } else if (req.validDays() != null) {
            if (req.validDays() <= 0) {
                throw new IllegalArgumentException("validDays must be positive");
            }
            until = from.plus(req.validDays(), ChronoUnit.DAYS);
        } else {
            throw new IllegalArgumentException("validUntil or validDays is required");
        }
        if (!until.isAfter(from)) {
            throw new IllegalArgumentException("validUntil must be after validFrom");
        }

        String key = RunService.locatorKey(req.method().trim().toUpperCase(),
                req.path().trim(), req.changeCode().trim(),
                req.pointer() == null ? "" : req.pointer());
        String exemptionKey = "EX-" + key.hashCode() + "-"
                + Integer.toHexString(
                        (req.candidateKey() + candidate.getContentHash()).hashCode());
        exemptionKey = "EX-" + com.gsb.engine.Canonical.sha256(List.of(
                req.candidateKey(), candidate.getContentHash(), key));

        Exemption existing = exemptions.findByExemptionKey(exemptionKey).orElse(null);
        Exemption exemption;
        String type;
        if (existing != null && Exemption.REVOKED.equals(existing.getStatus())) {
            existing.setStatus(Exemption.ACTIVE);
            existing.setReason(req.reason());
            existing.setOperator(req.operator());
            existing.setValidFrom(from);
            existing.setValidUntil(until);
            exemption = exemptions.save(existing);
            type = DecisionEvent.EXEMPTION_GRANTED;
        } else if (existing != null) {
            return existing;
        } else {
            exemption = new Exemption();
            exemption.setExemptionKey(exemptionKey);
            exemption.setCandidateKey(req.candidateKey());
            exemption.setMethod(req.method().trim().toUpperCase());
            exemption.setPath(req.path().trim());
            exemption.setChangeCode(req.changeCode().trim());
            exemption.setPointer(req.pointer() == null || req.pointer().isBlank()
                    ? null : req.pointer());
            exemption.setReason(req.reason());
            exemption.setOperator(req.operator());
            exemption.setBoundVersion(candidate.getContentHash());
            exemption.setStatus(Exemption.ACTIVE);
            exemption.setValidFrom(from);
            exemption.setValidUntil(until);
            exemption = exemptions.save(exemption);
            type = DecisionEvent.EXEMPTION_GRANTED;
        }

        Map<String, Object> payload = Json.map();
        payload.put("changeCode", exemption.getChangeCode());
        payload.put("exemptionKey", exemptionKey);
        payload.put("method", exemption.getMethod());
        payload.put("path", exemption.getPath());
        payload.put("pointer", exemption.getPointer() == null ? "" : exemption.getPointer());
        payload.put("validFrom", from.toString());
        payload.put("validUntil", until.toString());
        appendEvent(type, req.operator(), req.reason(), payload,
                exemption.getId(), null, candidate.getContentHash());
        return exemption;
    }

    @Transactional
    public Exemption revoke(String exemptionKey, RevokeRequest req) {
        requireText(req.operator(), "operator");
        requireText(req.reason(), "reason");
        Exemption exemption = exemptions.findByExemptionKey(exemptionKey)
                .orElseThrow(() -> new NotFoundException("exemption not found: " + exemptionKey));
        String previousStatus = exemption.getStatus();
        exemption.setStatus(Exemption.REVOKED);
        exemption = exemptions.save(exemption);

        Map<String, Object> payload = Json.map();
        payload.put("exemptionKey", exemptionKey);
        payload.put("previousStatus", previousStatus);
        appendEvent(DecisionEvent.EXEMPTION_REVOKED, req.operator(), req.reason(),
                payload, exemption.getId(), exemption.getBoundVersion(), null);
        return exemption;
    }

    @Transactional(readOnly = true)
    public List<Exemption> list(String candidateKey, String status) {
        List<Exemption> source = candidateKey == null || candidateKey.isBlank()
                ? exemptions.findAllByOrderByIdAsc()
                : exemptions.findByCandidateKeyOrderByIdAsc(candidateKey);
        return source.stream()
                .filter(ex -> status == null || status.isBlank()
                        || status.equals(effectiveStatus(ex)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DecisionEvent> events() {
        return events.findAllByOrderByIdAsc();
    }

    /**
     * Derived effective status: expired window -> EXPIRED; changed candidate
     * content or locator that no longer matches a current change -> PENDING.
     * PENDING is computed, never string-similarity based.
     */
    public String effectiveStatus(Exemption ex) {
        if (Exemption.REVOKED.equals(ex.getStatus())) {
            return Exemption.REVOKED;
        }
        if (ex.getValidUntil() != null && ex.getValidUntil().isBefore(Instant.now())) {
            return Exemption.EXPIRED;
        }
        SpecDocument current = specs.findBySpecKey(ex.getCandidateKey()).orElse(null);
        if (current == null || !current.getContentHash().equals(ex.getBoundVersion())) {
            return Exemption.PENDING;
        }
        return Exemption.ACTIVE;
    }

    private void appendEvent(String type, String operator, String reason,
                             Map<String, Object> payload, Long exemptionId,
                             String fromVersion, String toVersion) {
        String eventKey = "EV-" + com.gsb.engine.Canonical.sha256(List.of(
                type, operator, reason, payload,
                exemptionId == null ? "" : exemptionId,
                fromVersion == null ? "" : fromVersion,
                toVersion == null ? "" : toVersion));
        if (events.findAllByOrderByIdAsc().stream()
                .anyMatch(e -> e.getEventKey().equals(eventKey))) {
            return;
        }
        DecisionEvent event = new DecisionEvent();
        event.setEventKey(eventKey);
        event.setType(type);
        event.setOperator(operator);
        event.setReason(reason);
        event.setPayloadJson(Json.write(payload));
        event.setRelatedExemptionId(exemptionId);
        event.setFromVersion(fromVersion);
        event.setToVersion(toVersion);
        events.save(event);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
