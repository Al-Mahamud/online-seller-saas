package com.mojud.notifications;

import com.mojud.shared.paging.Cursor;
import com.mojud.shared.paging.CursorPage;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.CurrentBusiness;
import com.mojud.tenancy.RequiresRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The signed-in member's notifications in the selected business: the bell in the app. */
@RestController
@RequiresRole(BusinessRole.STAFF)
class NotificationController {

    record Item(UUID id, String type, String title, JsonNode payload, Instant at, boolean read) {
    }

    record Page(List<Item> items, String nextCursor, long unread) {
    }

    private record Row(UUID id, String type, String payload, Instant at, boolean read) {
    }

    private static final Instant END_OF_TIME = Instant.parse("9999-12-31T00:00:00Z");
    private static final UUID LAST_ID = new UUID(-1L, -1L);

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final MessageSource messages;
    private final Clock clock;

    NotificationController(DataSource dataSource, JsonMapper json, MessageSource messages, Clock clock) {
        this.jdbc = JdbcClient.create(dataSource);
        this.json = json;
        this.messages = messages;
        this.clock = clock;
    }

    /** Newest first, with how many are unread. Plain SQL: the business is passed by hand (RLS also applies). */
    @GetMapping("/api/v1/notifications")
    @Transactional(readOnly = true)
    Page list(@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        CurrentBusiness me = CurrentBusiness.require();
        List<String> audiences = visibleAudiences(me.role());
        int pageSize = CursorPage.limit(limit);
        Cursor after = Cursor.decode(cursor);
        List<Row> rows = jdbc.sql("""
                        select n.id, n.type, n.payload::text, n.created_at, r.user_id is not null
                        from notifications n
                        left join notification_reads r on r.notification_id = n.id and r.user_id = :user
                        where n.business_id = :business and n.audience in (:audiences)
                          and (n.created_at, n.id) < (:afterAt, :afterId)
                        order by n.created_at desc, n.id desc
                        limit :fetch
                        """)
                .param("user", me.userId())
                .param("business", me.businessId())
                .param("audiences", audiences)
                .param("afterAt", Timestamp.from(after == null ? END_OF_TIME : after.sortKeyAsInstant()))
                .param("afterId", after == null ? LAST_ID : after.id())
                .param("fetch", pageSize + 1)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant(), rs.getBoolean(5)))
                .list();
        long unread = jdbc.sql("""
                        select count(*) from notifications n
                        where n.business_id = :business and n.audience in (:audiences)
                          and not exists (select 1 from notification_reads r where r.notification_id = n.id and r.user_id = :user)
                        """)
                .param("business", me.businessId()).param("audiences", audiences).param("user", me.userId())
                .query(Long.class).single();
        CursorPage<Row> page = CursorPage.of(rows, pageSize, row -> new Cursor(row.at().toString(), row.id()));
        Locale locale = LocaleContextHolder.getLocale();
        return new Page(page.items().stream().map(row -> {
            JsonNode payload = json.readTree(row.payload());
            return new Item(row.id(), row.type(), title(row.type(), payload, locale), payload, row.at(), row.read());
        }).toList(), page.nextCursor(), unread);
    }

    /** Either {@code ids} or {@code all: true}. Ids the member cannot see are ignored. */
    record ReadRequest(@Size(max = 200) List<UUID> ids, Boolean all) {
    }

    @PostMapping("/api/v1/notifications/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void markRead(@Valid @RequestBody ReadRequest body) {
        CurrentBusiness me = CurrentBusiness.require();
        boolean all = Boolean.TRUE.equals(body.all());
        List<UUID> ids = body.ids() == null ? List.of() : body.ids();
        if (!all && ids.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        insert into notification_reads (business_id, notification_id, user_id, read_at)
                        select n.business_id, n.id, :user, :now from notifications n
                        where n.business_id = :business and n.audience in (:audiences)
                          and (:all or n.id in (:ids))
                        on conflict (notification_id, user_id) do nothing
                        """)
                .param("user", me.userId())
                .param("now", Timestamp.from(clock.instant()))
                .param("business", me.businessId())
                .param("audiences", visibleAudiences(me.role()))
                .param("all", all)
                .param("ids", ids.isEmpty() ? List.of(new UUID(0, 0)) : ids)
                .update();
    }

    /** A member sees notifications meant for their role or any lower one. */
    private static List<String> visibleAudiences(BusinessRole role) {
        return Arrays.stream(BusinessRole.values()).filter(role::atLeast).map(Enum::name).toList();
    }

    /** {@code notification.<type>} with the payload's {@code args} array in order. */
    private String title(String type, JsonNode payload, Locale locale) {
        JsonNode args = payload.path("args");
        Object[] values = new Object[args.size()];
        for (int i = 0; i < args.size(); i++) {
            values[i] = args.get(i).isNumber() ? args.get(i).numberValue() : args.get(i).asString();
        }
        return messages.getMessage("notification." + type, values, type, locale);
    }
}
