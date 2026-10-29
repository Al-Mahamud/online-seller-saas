package com.mojud.tenancy.membership;

import com.mojud.shared.tenant.TenantScope;
import com.mojud.tenancy.BusinessMembers;
import com.mojud.tenancy.BusinessRole;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class MemberQueries implements BusinessMembers {

    private final JdbcClient jdbc;

    MemberQueries(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public List<UUID> withRoleAtLeast(BusinessRole role) {
        record Member(UUID userId, BusinessRole role) {
        }
        return jdbc.sql("select user_id, role from memberships where business_id = ? and status = 'ACTIVE' order by created_at")
                .param(TenantScope.current().orElseThrow())
                .query((rs, n) -> new Member(rs.getObject(1, UUID.class), BusinessRole.valueOf(rs.getString(2))))
                .list().stream()
                .filter(member -> member.role().atLeast(role))
                .map(Member::userId)
                .toList();
    }
}
