package com.mojud.audit.system;

import com.mojud.audit.AdminAudit;
import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.shared.id.Ids;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/** {@link AdminAudit} with the system role: the log spans businesses. */
@Component
class SystemAdminAudit implements AdminAudit {

    private final SystemDatabase database;
    private final JsonMapper json;
    private final Clock clock;

    SystemAdminAudit(SystemDatabase database, JsonMapper json, Clock clock) {
        this.database = database;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void record(UUID adminId, String action, String targetType, String targetId, UUID businessId, Object... details) {
        Map<String, Object> pairs = new LinkedHashMap<>();
        for (int i = 0; i + 1 < details.length; i += 2) {
            if (details[i + 1] != null) {
                pairs.put(String.valueOf(details[i]), String.valueOf(details[i + 1]));
            }
        }
        String ip = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request
                ? request.getRequest().getRemoteAddr()
                : null;
        database.jdbc().sql("""
                        insert into admin_audit (id, admin_id, action, target_type, target_id, target_business_id, details,
                                                 client_ip, created_at)
                        values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                        """)
                .params(Ids.newId(), adminId, action, targetType, targetId, businessId, json.writeValueAsString(pairs), ip,
                        Timestamp.from(clock.instant()))
                .update();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Entry> recent(UUID businessId, int limit) {
        return database.jdbc().sql("""
                        select a.id, a.admin_id, a.action, a.target_type, a.target_id, a.target_business_id, a.details::text,
                               a.created_at, u.name
                        from admin_audit a left join users u on u.id = a.admin_id
                        where (cast(? as uuid) is null or a.target_business_id = ?)
                        order by a.created_at desc, a.id desc limit ?
                        """)
                .params(businessId, businessId, limit)
                .query((rs, n) -> new Entry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(9), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class),
                        json.readValue(rs.getString(7), Map.class), rs.getTimestamp(8).toInstant()))
                .list();
    }
}
