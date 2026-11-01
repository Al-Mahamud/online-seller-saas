package com.mojud.shared.web;

import com.fasterxml.jackson.databind.JavaType;
import io.swagger.v3.core.jackson.TypeNameResolver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Schema names in the API description. Request and response records are nested in the class that
 * uses them and share short names ({@code ProductController.CreateRequest},
 * {@code CustomerController.CreateRequest}); by simple name they would collapse into one schema.
 * Nested types are named after their enclosing classes instead, without "Controller", "Views" or
 * "Service": {@code ProductCreateRequest}, {@code OrderSummary}, and {@code AdminMetrics} for
 * {@code AdminMetricsController.Metrics}.
 */
final class ApiTypeNames extends TypeNameResolver {

    /**
     * One for the whole JVM: swagger's model converters are static, so with several application
     * contexts (tests) the one registered last serves them all.
     */
    static final ApiTypeNames INSTANCE = new ApiTypeNames();

    private static final List<String> SUFFIXES = List.of("Controller", "Views", "Service");

    private final Map<String, Class<?>> named = new ConcurrentHashMap<>();

    private ApiTypeNames() {
    }

    /** Every schema name given out, generic ones included, with the class it describes. */
    private final Map<String, Class<?>> schemas = new ConcurrentHashMap<>();

    /** The class behind a schema, such as {@code PagedResult} for {@code PagedResultOrderSummary}. */
    Optional<Class<?>> typeOf(String schemaName) {
        return Optional.ofNullable(schemas.get(schemaName));
    }

    @Override
    public String nameForType(JavaType type, Set<Options> options) {
        String name = super.nameForType(type, options);
        schemas.putIfAbsent(name, type.getRawClass());
        return name;
    }

    @Override
    protected String getNameOfClass(Class<?> type) {
        String name = type.getEnclosingClass() == null || !type.getPackageName().startsWith("com.mojud")
                ? super.getNameOfClass(type)
                : nestedName(type);
        Class<?> earlier = named.putIfAbsent(name, type);
        if (earlier != null && earlier != type) {
            throw new IllegalStateException("Two API types would be named " + name + ": " + earlier.getName()
                    + " and " + type.getName() + "; rename one or give it @Schema(name = ...)");
        }
        return name;
    }

    private static String nestedName(Class<?> type) {
        String name = type.getSimpleName();
        for (Class<?> outer = type.getEnclosingClass(); outer != null; outer = outer.getEnclosingClass()) {
            String prefix = withoutSuffix(outer.getSimpleName());
            if (prefix.endsWith(name)) {
                name = prefix;
            } else if (!name.startsWith(prefix)) {
                name = prefix + name;
            }
        }
        return name;
    }

    private static String withoutSuffix(String name) {
        for (String suffix : SUFFIXES) {
            if (name.endsWith(suffix) && name.length() > suffix.length()) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
    }
}
