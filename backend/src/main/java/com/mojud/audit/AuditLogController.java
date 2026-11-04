package com.mojud.audit;

import com.mojud.identity.UserDirectory;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.paging.PageQuery;
import com.mojud.shared.paging.PagedResult;
import com.mojud.shared.time.DhakaTime;
import com.mojud.tenancy.BusinessMembers;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.CurrentBusiness;
import com.mojud.tenancy.RequiresRole;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The selected business's activity log, newest first; owners only (backend-plan.md §7). Narrowed
 * by who did it, what kind of thing ({@link Kind}), when (days in Dhaka) and words in its details,
 * such as an order number or a SKU.
 */
@RestController
class AuditLogController {

    /** What an entry is about, from its action: the filter chips and colours of the page. */
    enum Kind { ORDERS, STOCK, PRODUCTS, CUSTOMERS, TEAM, SETTINGS, PLAN }

    /** The same grouping in SQL, so the filter and the counts run in the database. */
    private static final String KIND = """
            case
              when action like 'order.%' or action like 'payment.%' or action like 'cod.%' then 'ORDERS'
              when action like 'stock.%' or action like 'damage.%' then 'STOCK'
              when action like 'variant.%' or action like 'product.%' or action like 'import.%'
                or action like 'category.%' or action like 'tag.%' then 'PRODUCTS'
              when action like 'customer.%' then 'CUSTOMERS'
              when action like 'staff.%' or action in ('business.permissions_changed', 'business.ownership_transferred')
                then 'TEAM'
              when action like 'invoice.%' or action like 'subscription.%' or action like 'plan.%'
                or action like 'trial.%' then 'PLAN'
              else 'SETTINGS'
            end""";

    /** {@code member}: still in the business; false for someone who has left. */
    record Actor(UUID id, @Nullable String name, boolean member) {
    }

    /** {@code subject}: the name of the person an entry is about, when it is about one. */
    record Entry(UUID id, Instant at, @Nullable Actor actor, String action, Kind kind, @Nullable String entityType,
            @Nullable UUID entityId, @Nullable String subject, @Nullable JsonNode details) {
    }

    record Count(@Nullable Actor actor, long count) {
    }

    record KindCount(Kind kind, long count) {
    }

    /** Today and the last 7 days, in Dhaka; {@code actors}: everyone who ever appears, for the filter. */
    record Summary(long today, long lastSevenDays, List<Count> people, List<KindCount> kinds, List<Actor> actors) {
    }

    private record Row(UUID id, Instant at, UUID actorId, String action, Kind kind, String entityType, UUID entityId,
            String details) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final UserDirectory users;
    private final BusinessMembers members;
    private final Clock clock;

    AuditLogController(DataSource dataSource, JsonMapper json, UserDirectory users, BusinessMembers members,
            Clock clock) {
        this.jdbc = JdbcClient.create(dataSource);
        this.json = json;
        this.users = users;
        this.members = members;
        this.clock = clock;
    }

    /**
     * {@code actor}: a member's id, or {@code SYSTEM} for what nobody did by hand. {@code from} and
     * {@code to}: days in Dhaka, both included. {@code q}: words in the details.
     */
    @GetMapping("/api/v1/business/audit-log")
    @RequiresRole(BusinessRole.OWNER)
    @Transactional(readOnly = true)
    PagedResult<Entry> list(@ParameterObject PageQuery paging, @RequestParam(required = false) @Nullable String actor,
            @RequestParam(required = false) @Nullable Kind kind,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate to,
            @RequestParam(required = false) @Nullable String q) {
        Filter filter = new Filter(actor, kind, from, to, q);
        // business_id by hand: plain SQL gets no Hibernate tenant filter (row-level security still applies).
        List<Row> rows = filter.bind(jdbc.sql("select id, created_at, actor_id, action, " + KIND
                        + ", entity_type, entity_id, details::text from audit_logs where " + filter.where()
                        + " order by created_at desc, id desc limit :limit offset :offset"))
                .param("limit", paging.limit())
                .param("offset", paging.offset())
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(),
                        rs.getObject(3, UUID.class), rs.getString(4), Kind.valueOf(rs.getString(5)), rs.getString(6),
                        rs.getObject(7, UUID.class), rs.getString(8)))
                .list();
        long total = filter.bind(jdbc.sql("select count(*) from audit_logs where " + filter.where()))
                .query(Long.class).single();
        Set<UUID> people = new HashSet<>();
        rows.forEach(row -> {
            if (row.actorId() != null) {
                people.add(row.actorId());
            }
            if ("user".equals(row.entityType()) && row.entityId() != null) {
                people.add(row.entityId());
            }
        });
        Map<UUID, UserDirectory.UserSummary> named = users.summaries(people);
        Set<UUID> current = Set.copyOf(members.withRoleAtLeast(BusinessRole.STAFF));
        List<Entry> entries = rows.stream().map(row -> new Entry(row.id(), row.at(),
                row.actorId() == null ? null : actor(row.actorId(), named, current), row.action(), row.kind(),
                row.entityType(), row.entityId(),
                "user".equals(row.entityType()) && named.containsKey(row.entityId())
                        ? named.get(row.entityId()).name() : null,
                json.readTree(row.details()))).toList();
        return PagedResult.of(entries, paging, total);
    }

    @GetMapping("/api/v1/business/audit-log/summary")
    @RequiresRole(BusinessRole.OWNER)
    @Transactional(readOnly = true)
    Summary summary() {
        UUID business = CurrentBusiness.require().businessId();
        LocalDate today = LocalDate.now(clock.withZone(DhakaTime.ZONE));
        Timestamp startOfToday = Timestamp.from(today.atStartOfDay(DhakaTime.ZONE).toInstant());
        Timestamp weekStart = Timestamp.from(today.minusDays(6).atStartOfDay(DhakaTime.ZONE).toInstant());
        long todays = jdbc.sql("select count(*) from audit_logs where business_id = ? and created_at >= ?")
                .params(business, startOfToday).query(Long.class).single();
        long week = jdbc.sql("select count(*) from audit_logs where business_id = ? and created_at >= ?")
                .params(business, weekStart).query(Long.class).single();
        Map<UUID, Long> byActor = new LinkedHashMap<>();
        long[] bySystem = {0};
        jdbc.sql("""
                        select actor_id, count(*) from audit_logs where business_id = ? and created_at >= ?
                        group by actor_id order by count(*) desc""")
                .params(business, weekStart)
                .query((rs, n) -> {
                    UUID id = rs.getObject(1, UUID.class);
                    if (id == null) {
                        bySystem[0] = rs.getLong(2);
                    } else {
                        byActor.put(id, rs.getLong(2));
                    }
                    return null;
                })
                .list();
        List<KindCount> kinds = jdbc.sql("select " + KIND + " as kind, count(*) from audit_logs"
                        + " where business_id = ? and created_at >= ? group by 1 order by 2 desc")
                .params(business, weekStart)
                .query((rs, n) -> new KindCount(Kind.valueOf(rs.getString(1)), rs.getLong(2)))
                .list();
        List<UUID> everyone = jdbc.sql("select distinct actor_id from audit_logs where business_id = ? and actor_id is not null")
                .param(business).query(UUID.class).list();
        Map<UUID, UserDirectory.UserSummary> named = users.summaries(everyone);
        Set<UUID> current = Set.copyOf(members.withRoleAtLeast(BusinessRole.STAFF));
        List<Count> people = Stream.concat(
                byActor.entrySet().stream().map(entry -> new Count(actor(entry.getKey(), named, current), entry.getValue())),
                bySystem[0] > 0 ? Stream.of(new Count(null, bySystem[0])) : Stream.empty()).toList();
        List<Actor> actors = everyone.stream().map(id -> actor(id, named, current))
                .sorted((a, b) -> Objects.requireNonNullElse(a.name(), "").compareToIgnoreCase(Objects.requireNonNullElse(b.name(), "")))
                .toList();
        return new Summary(todays, week, people, kinds, actors);
    }

    private static Actor actor(UUID id, Map<UUID, UserDirectory.UserSummary> named, Set<UUID> current) {
        UserDirectory.UserSummary person = named.get(id);
        return new Actor(id, person == null ? null : person.name(), current.contains(id));
    }

    /** The WHERE clause for the filters given, and its parameters. */
    private final class Filter {

        private final @Nullable String actor;
        private final @Nullable Kind kind;
        private final @Nullable LocalDate from;
        private final @Nullable LocalDate to;
        private final @Nullable String q;

        Filter(@Nullable String actor, @Nullable Kind kind, @Nullable LocalDate from, @Nullable LocalDate to,
                @Nullable String q) {
            this.actor = actor == null || actor.isBlank() ? null : actor.strip();
            this.kind = kind;
            this.from = from;
            this.to = to;
            this.q = q == null || q.isBlank() ? null : q.strip();
            if (this.actor != null && !this.actor.equals("SYSTEM")) {
                try {
                    UUID.fromString(this.actor);
                } catch (IllegalArgumentException e) {
                    throw ApiException.invalidField("actor", "field.invalid_value");
                }
            }
            if (from != null && to != null && to.isBefore(from)) {
                throw ApiException.invalidField("to", "field.invalid_value");
            }
        }

        String where() {
            StringBuilder where = new StringBuilder("business_id = :business");
            if ("SYSTEM".equals(actor)) {
                where.append(" and actor_id is null");
            } else if (actor != null) {
                where.append(" and actor_id = :actor");
            }
            if (kind != null) {
                where.append(" and (").append(KIND).append(") = :kind");
            }
            if (from != null) {
                where.append(" and created_at >= :from");
            }
            if (to != null) {
                where.append(" and created_at < :to");
            }
            if (q != null) {
                where.append(" and details::text ilike :q");
            }
            return where.toString();
        }

        JdbcClient.StatementSpec bind(JdbcClient.StatementSpec sql) {
            sql = sql.param("business", CurrentBusiness.require().businessId());
            if (actor != null && !"SYSTEM".equals(actor)) {
                sql = sql.param("actor", UUID.fromString(actor));
            }
            if (kind != null) {
                sql = sql.param("kind", kind.name());
            }
            if (from != null) {
                sql = sql.param("from", Timestamp.from(from.atStartOfDay(DhakaTime.ZONE).toInstant()));
            }
            if (to != null) {
                sql = sql.param("to", Timestamp.from(to.plusDays(1).atStartOfDay(DhakaTime.ZONE).toInstant()));
            }
            if (q != null) {
                sql = sql.param("q", "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            }
            return sql;
        }
    }
}
