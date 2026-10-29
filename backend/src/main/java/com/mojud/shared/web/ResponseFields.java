package com.mojud.shared.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.customizers.OpenApiCustomizer;

/**
 * What the API description says about fields in responses, so the frontend's generated types are
 * exact. Jackson writes every field of a response record, null or not, so each one is required;
 * a field that can be null says so with {@code @Nullable} (org.jspecify) and becomes
 * {@code type | null}; a field left out when null ({@code @JsonInclude(NON_NULL)}, such as cost
 * prices hidden from staff) stays optional. Request bodies are left as they are: their required
 * fields come from {@code @NotNull} and {@code @NotBlank}.
 */
final class ResponseFields implements OpenApiCustomizer {

    private static final String REF_PREFIX = "#/components/schemas/";

    private final ApiTypeNames typeNames;

    ResponseFields(ApiTypeNames typeNames) {
        this.typeNames = typeNames;
    }

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        Map<String, Schema> schemas = openApi.getComponents().getSchemas();
        for (String name : reachableFromResponses(openApi, schemas)) {
            Schema<?> schema = schemas.get(name);
            if (schema.getProperties() == null) {
                continue;
            }
            Class<?> type = typeNames.typeOf(name).orElse(null);
            List<String> required = new ArrayList<>();
            for (String property : schema.getProperties().keySet()) {
                RecordComponent component = component(type, property);
                if (component != null && leftOutWhenNull(component)) {
                    continue;
                }
                required.add(property);
                if (component != null && component.getAnnotatedType().isAnnotationPresent(Nullable.class)) {
                    schema.getProperties().put(property, nullable(schema.getProperties().get(property)));
                }
            }
            schema.setRequired(required);
        }
    }

    private static Set<String> reachableFromResponses(OpenAPI openApi, Map<String, Schema> schemas) {
        Deque<Schema> pending = new ArrayDeque<>();
        if (openApi.getPaths() != null) {
            openApi.getPaths().values().forEach(path -> path.readOperations().stream()
                    .map(Operation::getResponses)
                    .filter(responses -> responses != null)
                    .flatMap(responses -> responses.values().stream())
                    .map(ApiResponse::getContent)
                    .filter(content -> content != null)
                    .flatMap(content -> content.values().stream())
                    .map(MediaType::getSchema)
                    .filter(schema -> schema != null)
                    .forEach(pending::add));
        }
        Set<String> reached = new LinkedHashSet<>();
        Set<Schema> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            Schema schema = pending.pop();
            if (!seen.add(schema)) {
                continue;
            }
            String ref = schema.get$ref();
            if (ref != null && ref.startsWith(REF_PREFIX)) {
                String name = ref.substring(REF_PREFIX.length());
                if (reached.add(name) && schemas.get(name) != null) {
                    pending.add(schemas.get(name));
                }
            }
            if (schema.getProperties() != null) {
                pending.addAll(schema.getProperties().values());
            }
            if (schema.getItems() != null) {
                pending.add(schema.getItems());
            }
            if (schema.getAdditionalProperties() instanceof Schema values) {
                pending.add(values);
            }
            for (List<Schema> parts : List.of(nonNull(schema.getAllOf()), nonNull(schema.getAnyOf()), nonNull(schema.getOneOf()))) {
                parts.forEach(pending::add);
            }
        }
        return reached;
    }

    private static List<Schema> nonNull(List<Schema> schemas) {
        return schemas == null ? List.of() : schemas;
    }

    private static RecordComponent component(Class<?> type, String property) {
        if (type == null || !type.isRecord()) {
            return null;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            if (component.getName().equals(property)) {
                return component;
            }
        }
        return null;
    }

    private static boolean leftOutWhenNull(RecordComponent component) {
        JsonInclude include = component.getAnnotation(JsonInclude.class);
        if (include == null && component.getAccessor() != null) {
            include = component.getAccessor().getAnnotation(JsonInclude.class);
        }
        return include != null && include.value() != JsonInclude.Include.ALWAYS;
    }

    /** {@code type: [string, "null"]}, or for a reference {@code anyOf: [{$ref}, {type: "null"}]}. */
    private static Schema<?> nullable(Schema<?> property) {
        if (property.get$ref() != null) {
            return new Schema<>().anyOf(List.of(property, new Schema<>().types(Set.of("null"))));
        }
        Set<String> types = property.getTypes() != null ? new LinkedHashSet<>(property.getTypes())
                : property.getType() != null ? new LinkedHashSet<>(Set.of(property.getType())) : new LinkedHashSet<>();
        types.add("null");
        property.setTypes(types);
        return property;
    }
}
