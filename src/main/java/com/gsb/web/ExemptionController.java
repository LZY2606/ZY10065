package com.gsb.web;

import com.gsb.model.DecisionEvent;
import com.gsb.model.Exemption;
import com.gsb.service.ExemptionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@RestController
@RequestMapping("/api")
public class ExemptionController {

    private final ExemptionService exemptions;

    public ExemptionController(ExemptionService exemptions) {
        this.exemptions = exemptions;
    }

    @PostMapping("/exemptions")
    public Map<String, Object> grant(@RequestBody Dtos.GrantBody body) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        Exemption exemption = exemptions.grant(body.toRequest());
        return toMap(exemption);
    }

    @GetMapping("/exemptions")
    public List<Map<String, Object>> list(@RequestParam(required = false) String candidateKey,
                                          @RequestParam(required = false) String status) {
        return exemptions.list(candidateKey, status).stream()
                .map(this::toMap)
                .toList();
    }

    @PostMapping("/exemptions/{key}/revoke")
    public Map<String, Object> revoke(@PathVariable String key,
                                      @RequestBody Dtos.RevokeBody body) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        return toMap(exemptions.revoke(key, body.toRequest()));
    }

    @GetMapping("/events")
    public List<Map<String, Object>> events() {
        return exemptions.events().stream()
                .map(ExemptionController::eventToMap)
                .toList();
    }

    private Map<String, Object> toMap(Exemption ex) {
        Map<String, Object> map = new TreeMap<>();
        map.put("boundVersion", ex.getBoundVersion());
        map.put("candidateKey", ex.getCandidateKey());
        map.put("changeCode", ex.getChangeCode());
        map.put("effectiveStatus", exemptions.effectiveStatus(ex));
        map.put("exemptionKey", ex.getExemptionKey());
        map.put("method", ex.getMethod());
        map.put("operator", ex.getOperator());
        map.put("path", ex.getPath());
        map.put("pointer", ex.getPointer() == null ? "" : ex.getPointer());
        map.put("reason", ex.getReason());
        map.put("status", ex.getStatus());
        map.put("validFrom", ex.getValidFrom() == null ? "" : ex.getValidFrom().toString());
        map.put("validUntil", ex.getValidUntil() == null ? "" : ex.getValidUntil().toString());
        return map;
    }

    private static Map<String, Object> eventToMap(DecisionEvent event) {
        Map<String, Object> map = new TreeMap<>();
        map.put("createdAt", event.getCreatedAt() == null ? "" : event.getCreatedAt().toString());
        map.put("eventKey", event.getEventKey());
        map.put("fromVersion", event.getFromVersion() == null ? "" : event.getFromVersion());
        map.put("operator", event.getOperator());
        map.put("payloadJson", event.getPayloadJson());
        map.put("reason", event.getReason());
        map.put("relatedExemptionId",
                event.getRelatedExemptionId() == null ? "" : event.getRelatedExemptionId());
        map.put("toVersion", event.getToVersion() == null ? "" : event.getToVersion());
        map.put("type", event.getType());
        return map;
    }
}
