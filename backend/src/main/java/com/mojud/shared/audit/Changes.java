package com.mojud.shared.audit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Before and after values for an audit entry, keeping only fields that really changed:
 * {@code {"name": {"from": "Old", "to": "New"}}}. Enums are written by name.
 */
public final class Changes {

    private final Map<String, Object> fields = new LinkedHashMap<>();

    public Changes field(String name, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("from", plain(before));
            change.put("to", plain(after));
            fields.put(name, Collections.unmodifiableMap(change));
        }
        return this;
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(fields);
    }

    private static Object plain(Object value) {
        return value instanceof Enum<?> e ? e.name() : value;
    }
}
