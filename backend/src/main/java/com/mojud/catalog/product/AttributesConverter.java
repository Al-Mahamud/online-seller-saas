package com.mojud.catalog.product;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** A variant's option values as JSON text; the column is jsonb (cast on write in ProductVariant). */
@Converter
class AttributesConverter implements AttributeConverter<Map<String, String>, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<LinkedHashMap<String, String>> MAP = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(Map<String, String> attributes) {
        return JSON.writeValueAsString(attributes == null ? Map.of() : attributes);
    }

    @Override
    public Map<String, String> convertToEntityAttribute(String json) {
        return json == null ? new LinkedHashMap<>() : JSON.readValue(json, MAP);
    }
}
