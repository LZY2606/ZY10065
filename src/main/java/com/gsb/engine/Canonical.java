package com.gsb.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic canonicalization. All replayable hashes in the system are derived
 * from {@link #canonical(Object)}: maps are emitted with sorted keys, lists keep
 * their (already deterministic) order. Wall-clock time must never be passed here.
 */
public final class Canonical {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private Canonical() {
    }

    /** Returns a structurally normalized copy: every Map becomes a TreeMap. */
    @SuppressWarnings("unchecked")
    public static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> out = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), sorted(e.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(sorted(item));
            }
            return out;
        }
        return value;
    }

    public static String canonical(Object value) {
        try {
            Object normalized = sorted(value);
            return MAPPER.writeValueAsString(normalized);
        } catch (Exception e) {
            throw new IllegalStateException("canonical serialization failed", e);
        }
    }

    public static String sha256(Object value) {
        return sha256Hex(canonical(value).getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Text(String raw) {
        return sha256Hex(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
