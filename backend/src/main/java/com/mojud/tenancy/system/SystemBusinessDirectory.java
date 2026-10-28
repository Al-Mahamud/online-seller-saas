package com.mojud.tenancy.system;

import com.mojud.identity.UserDirectory;
import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.shared.email.EmailAddress;
import com.mojud.shared.phone.PhoneNumber;
import com.mojud.tenancy.BusinessDirectory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
    public List<BusinessSummary> search(String q, int limit) {
        String query = q == null ? "" : q.strip();
        Optional<UUID> owner = Optional.empty();
        if (!query.isEmpty()) {
            String contact = query.contains("@")
                    ? EmailAddress.tryParse(query).map(EmailAddress::value).orElse(null)
                    : PhoneNumber.tryParse(query).map(PhoneNumber::e164).orElse(null);
            owner = contact == null ? Optional.empty() : users.findByVerifiedContact(contact);
        }
        List<Row> rows;
        if (owner.isPresent()) {
            rows = database.jdbc().sql(SELECT + """
                            where b.id in (select business_id from memberships where user_id = ? and role = 'OWNER')
                            order by b.created_at desc limit ?
                            """)
                    .params(owner.get(), limit).query(this::row).list();
        } else {
            String pattern = "%" + query.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            rows = database.jdbc().sql(SELECT + "where lower(b.name) like ? order by b.created_at desc limit ?")
                    .params(pattern, limit).query(this::row).list();
        }
        return summaries(rows);
    }

    @Override
    public Optional<BusinessSummary> find(UUID businessId) {
        return database.jdbc().sql(SELECT + "where b.id = ?").param(businessId).query(this::row).optional()
                .map(row -> summaries(List.of(row)).get(0));
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
