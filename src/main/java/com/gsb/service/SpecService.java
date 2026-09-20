package com.gsb.service;

import com.gsb.engine.Canonical;
import com.gsb.engine.Fingerprint;
import com.gsb.engine.SpecParser;
import com.gsb.model.SpecDocument;
import com.gsb.model.SpecDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class SpecService {

    private final SpecDocumentRepository specs;

    public SpecService(SpecDocumentRepository specs) {
        this.specs = specs;
    }

    public record UpsertResult(SpecDocument document, boolean created, boolean changed) {
    }

    /**
     * Idempotent import: identical content for the same key writes nothing;
     * changed content replaces the text, hash and bumps versionSeq.
     */
    @Transactional
    public UpsertResult upsert(String key, String rawText) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("spec key is required");
        }
        if (rawText == null) {
            throw new IllegalArgumentException("rawText is required");
        }
        Fingerprint fp;
        try {
            fp = SpecParser.fingerprint(rawText);
        } catch (Exception e) {
            throw new IllegalArgumentException("spec cannot be parsed: " + e.getMessage(), e);
        }
        Map<String, Object> fpMap = new TreeMap<>();
        fpMap.put("bytes", fp.bytes());
        fpMap.put("openapi", fp.openapi());
        fpMap.put("sha256", fp.sha256());
        fpMap.put("title", fp.title());
        fpMap.put("version", fp.version());
        String fingerprintJson = Json.write(fpMap);

        SpecDocument doc = specs.findBySpecKey(key).orElse(null);
        if (doc == null) {
            doc = new SpecDocument();
            doc.setSpecKey(key);
            doc.setContentHash(fp.sha256());
            doc.setRawText(rawText);
            doc.setFingerprintJson(fingerprintJson);
            doc.setVersionSeq(1);
            return new UpsertResult(specs.save(doc), true, true);
        }
        if (doc.getContentHash().equals(fp.sha256())) {
            return new UpsertResult(doc, false, false);
        }
        doc.setContentHash(fp.sha256());
        doc.setRawText(rawText);
        doc.setFingerprintJson(fingerprintJson);
        doc.setVersionSeq(doc.getVersionSeq() + 1);
        return new UpsertResult(specs.save(doc), false, true);
    }

    @Transactional(readOnly = true)
    public SpecDocument require(String key) {
        return specs.findBySpecKey(key)
                .orElseThrow(() -> new NotFoundException("spec not found: " + key));
    }

    @Transactional(readOnly = true)
    public List<SpecDocument> list() {
        return specs.findAllByOrderBySpecKeyAsc();
    }

    @Transactional
    public void delete(String key) {
        specs.findBySpecKey(key).ifPresent(specs::delete);
    }

    public static String contentHashOf(String rawText) {
        return Canonical.sha256Text(rawText);
    }
}
