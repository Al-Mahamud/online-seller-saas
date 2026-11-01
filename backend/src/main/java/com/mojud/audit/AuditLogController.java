package com.mojud.audit;

import com.mojud.identity.UserDirectory;
import com.mojud.shared.paging.PageQuery;
import com.mojud.shared.paging.PagedResult;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.CurrentBusiness;
import com.mojud.tenancy.RequiresRole;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The selected business's audit log, newest first; owners only (backend-plan.md §7). */
@RestController
class AuditLogController {

    record Actor(UUID id, String name) {
    }

    record Entry(UUID id, Instant at, @Nullable Actor actor, String action, @Nullable String entityType,
            @Nullable UUID entityId, @Nullable JsonNode details) {
    }

    private record Row(UUID id, Instant at, UUID actorId, String action, String entityType, UUID entityId,
            String details) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final UserDirectory users;

    AuditLogController(DataSource dataSource, JsonMapper json, UserDirectory users) {
        this.jdbc = JdbcClient.create(dataSource);
        this.json = json;
        this.users = users;
    }

    @GetMapping("/api/v1/business/audit-log")
    @RequiresRole(BusinessRole.OWNER)
    @Transactional(readOnly = true)
    PagedResult<Entry> list(@ParameterObject PageQuery paging) {
        UUID business = CurrentBusiness.require().businessId();
        // business_id by hand: plain SQL gets no Hibernate tenant filter (row-level security still applies).
        List<Row> rows = jdbc.sql("""
                        select id, created_at, actor_id, action, entity_type, entity_id, details::text
                        from audit_logs
                        where business_id = :business
                        order by created_at desc, id desc
                        limit :limit offset :offset
                        """)
                .param("business", business)
                .param("limit", paging.limit())
                .param("offset", paging.offset())
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class),
                        rs.getString(7)))
                .list();
        long total = jdbc.sql("select count(*) from audit_logs where business_id = :business")
                .param("business", business).query(Long.class).single();
        Map<UUID, UserDirectory.UserSummary> people = users.summaries(
                rows.stream().map(Row::actorId).filter(java.util.Objects::nonNull).distinct().toList());
        List<Entry> entries = rows.stream().map(row -> new Entry(row.id(), row.at(),
                row.actorId() == null ? null
                        : new Actor(row.actorId(), people.containsKey(row.actorId()) ? people.get(row.actorId()).name() : null),
                row.action(), row.entityType(), row.entityId(), json.readTree(row.details()))).toList();
        return PagedResult.of(entries, paging, total);
    }
}
