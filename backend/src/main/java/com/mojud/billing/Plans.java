package com.mojud.billing;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The plans (V26), read once: they change only with a migration (later the admin console). */
@Component
class Plans {

    /** {@code limits}: metric key to its limit, null when unlimited; {@code exports}: CSV exports allowed. */
    record Plan(String code, String nameEn, String nameBn, long priceMonthly, boolean isPublic, Map<String, Integer> limits,
            boolean exports) {

        Integer limit(Metric metric) {
            return limits.get(metric.key());
        }
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private volatile List<Plan> plans;

    Plans(DataSource dataSource, JsonMapper json) {
        this.jdbc = JdbcClient.create(dataSource);
        this.json = json;
    }

    /** After an administrator edits a plan. */
    void reload() {
        plans = null;
    }

    List<Plan> all() {
        List<Plan> loaded = plans;
        if (loaded == null) {
            loaded = jdbc.sql("select code, name_en, name_bn, price_monthly, is_public, limits::text from plans order by price_monthly")
                    .query((rs, n) -> plan(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                            rs.getBoolean(5), rs.getString(6)))
                    .list();
            plans = loaded;
        }
        return loaded;
    }

    Plan get(String code) {
        return all().stream().filter(plan -> plan.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No plan " + code));
    }

    private Plan plan(String code, String nameEn, String nameBn, long price, boolean isPublic, String limitsJson) {
        JsonNode node = json.readTree(limitsJson);
        Map<String, Integer> limits = new HashMap<>();
        for (Metric metric : Metric.values()) {
            JsonNode value = node.get(metric.key());
            limits.put(metric.key(), value == null || value.isNull() ? null : value.asInt());
        }
        return new Plan(code, nameEn, nameBn, price, isPublic, Collections.unmodifiableMap(limits),
                node.path("exports").asBoolean(false));
    }
}
