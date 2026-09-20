package com.gsb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gsb.engine.Canonical;

import java.util.TreeMap;

/**
 * Shared JSON support. Deterministic output is a hard invariant: maps are always
 * sorted and no wall-clock value is ever inserted into report payloads.
 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private Json() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(Canonical.sorted(value));
        } catch (Exception e) {
            throw new IllegalStateException("deterministic JSON write failed", e);
        }
    }

    public static TreeMap<String, Object> map() {
        return new TreeMap<>();
    }
}
