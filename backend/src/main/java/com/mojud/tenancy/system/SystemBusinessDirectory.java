package com.mojud.tenancy.system;

import com.mojud.shared.text.Text;
import com.mojud.identity.UserDirectory;
import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.shared.email.EmailAddress;
import com.mojud.shared.paging.PageQuery;
import com.mojud.shared.paging.PagedResult;
import com.mojud.shared.phone.PhoneNumber;
import com.mojud.tenancy.BusinessDirectory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** {@link BusinessDirectory} with the system role (it spans businesses). */
@Component
class SystemBusinessDirectory implements BusinessDirectory {

    private record Row(UUID id, String name, String phone, String address, String status, Instant createdAt, UUID ownerId,
            int members) {
    }

    private static final String SELECT = """
            select b.id, b.name, b.phone, b.address, b.status, b.created_at,
                   (select m.user_id from memberships m where m.business_id = b.id and m.role = 'OWNER' and m.status = 'ACTIVE' limit 1),
                   (select count(*) from memberships m where m.business_id = b.id and m.status = 'ACTIVE')
            from businesses b
            """;

    private final SystemDatabase database;
    private final UserDirectory users;

    SystemBusinessDirectory(SystemDatabase database, UserDirectory users) {
        this.database = database;
        this.users = users;
    }

    @Override
    public PagedResult<BusinessSummary> search(String q, PageQuery paging) {
        String query = q == null ? "" : q.strip();
        Optional<UUID> owner = Optional.empty();
        if (!query.isEmpty()) {
            String contact = query.contains("@")
                    ? EmailAddress.tryParse(query).map(EmailAddress::value).orElse(null)
                    : PhoneNumber.tryParse(query).map(PhoneNumber::e164).orElse(null);
            owner = contact == null ? Optional.empty() : users.findByVerifiedContact(contact);
            if (owner.isEmpty()) {
                owner = users.findByUserId(query);
            }
        }
        List<Row> rows;
        long total;
        if (owner.isPresent()) {
            String where = "where b.id in (select business_id from memberships where user_id = ? and role = 'OWNER') ";
            rows = database.jdbc().sql(SELECT + where + "order by b.created_at desc, b.id desc limit ? offset ?")
                    .params(owner.get(), paging.limit(), paging.offset()).query(this::row).list();
            total = database.jdbc().sql("select count(*) from businesses b " + where)
                    .params(owner.get()).query(Long.class).single();
        } else {
            String pattern = Text.containsPattern(query);
            String where = "where lower(b.name) like ? ";
            rows = database.jdbc().sql(SELECT + where + "order by b.created_at desc, b.id desc limit ? offset ?")
                    .params(pattern, paging.limit(), paging.offset()).query(this::row).list();
            total = database.jdbc().sql("select count(*) from businesses b " + where)
                    .params(pattern).query(Long.class).single();
        }
        return PagedResult.of(summaries(rows), paging, total);
    }

    @Override
    public Optional<BusinessSummary> find(UUID businessId) {
        return database.jdbc().sql(SELECT + "where b.id = ?").param(businessId).query(this::row).optional()
                .map(row -> summaries(List.of(row)).get(0));
    }

    @Override
    public long businesses() {
        return database.jdbc().sql("select count(*) from businesses").query(Long.class).single();
    }

    @Override
    public long createdSince(Instant since) {
        return database.jdbc().sql("select count(*) from businesses where created_at >= ?").param(Timestamp.from(since))
                .query(Long.class).single();
    }

    private Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getTimestamp(6).toInstant(), rs.getObject(7, UUID.class), rs.getInt(8));
    }

    private List<BusinessSummary> summaries(List<Row> rows) {
        Map<UUID, UserDirectory.UserSummary> owners = users.summaries(rows.stream().map(Row::ownerId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return rows.stream().map(row -> {
            UserDirectory.UserSummary owner = row.ownerId() == null ? null : owners.get(row.ownerId());
            return new BusinessSummary(row.id(), row.name(), row.phone(), row.address(), row.status(), row.createdAt(),
                    owner == null ? null : new Owner(owner.id(), owner.name(), owner.phone(), owner.email()), row.members());
        }).toList();
    }
}
