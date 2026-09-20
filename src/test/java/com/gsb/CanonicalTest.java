package com.gsb;

import com.gsb.engine.Canonical;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CanonicalTest {

    @Test
    void keyOrderAndIterationOrderDoNotChangeHash() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("z", 1);
        a.put("a", List.of(3, 2, 1));
        a.put("m", Map.of("y", 2, "x", 1));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("m", new LinkedHashMap<>(Map.of("x", 1, "y", 2)));
        b.put("a", List.of(3, 2, 1));
        b.put("z", 1);
        assertEquals(Canonical.sha256(a), Canonical.sha256(b));
    }

    @Test
    void distinctContentHasDistinctHash() {
        assertNotEquals(Canonical.sha256(Map.of("v", 1)), Canonical.sha256(Map.of("v", 2)));
    }
}
