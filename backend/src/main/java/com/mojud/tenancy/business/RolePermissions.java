package com.mojud.tenancy.business;

import com.mojud.shared.audit.AuditTrail;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.tenant.TenantScope;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.CurrentBusiness;
import com.mojud.tenancy.Permission;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What staff and managers may do in the business the request works in ({@link Permission}). Read
 * on every request by the tenancy filter, so a change counts from each member's next request. Only
 * the owner changes them.
 */
@Component
public class RolePermissions {

    /** Staff's and managers' areas, in {@link Permission} order. */
    public record Rules(List<Permission> staff, List<Permission> manager) {
    }

    private final JdbcClient jdbc;
    private final AuditTrail audit;

    RolePermissions(JdbcClient jdbc, AuditTrail audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** For a member with this role in the business of the open {@link TenantScope}. */
    public Set<Permission> forRole(BusinessRole role) {
        if (role == BusinessRole.OWNER) {
            return Permission.all();
        }
        Rules rules = read();
        return withNone(role == BusinessRole.MANAGER ? rules.manager() : rules.staff());
    }

    @Transactional(readOnly = true)
    public Rules read() {
        UUID businessId = TenantScope.current().orElseThrow();
        return jdbc.sql("select staff_permissions, manager_permissions from business_settings where business_id = ?")
                .param(businessId)
                .query((rs, row) -> new Rules(parse(rs.getArray(1)), parse(rs.getArray(2))))
                .optional()
                .orElseGet(() -> new Rules(List.copyOf(Permission.defaults(BusinessRole.STAFF)),
                        List.copyOf(Permission.defaults(BusinessRole.MANAGER))));
    }

    /** The owner's change; recorded in the audit log as before and after. */
    @Transactional
    Rules write(Set<Permission> staff, Set<Permission> manager) {
        CurrentBusiness business = CurrentBusiness.require();
        Rules before = read();
        Rules after = new Rules(sorted(staff), sorted(manager));
        int updated = jdbc.sql("""
                        update business_settings set staff_permissions = ?::text[], manager_permissions = ?::text[]
                        where business_id = ?""")
                .params(literal(after.staff()), literal(after.manager()), business.businessId())
                .update();
        if (updated == 0) {
            throw ApiException.notFound();
        }
        if (!before.equals(after)) {
            audit.record(business.businessId(), business.userId(), "business.permissions_changed", "business",
                    business.businessId(), "staff", after.staff(), "manager", after.manager(),
                    "staffBefore", before.staff(), "managerBefore", before.manager());
        }
        return after;
    }

    private static List<Permission> parse(Array array) throws SQLException {
        String[] names = (String[]) array.getArray();
        return Arrays.stream(names).map(Permission::valueOf).sorted().toList();
    }

    private static List<Permission> sorted(Set<Permission> permissions) {
        return permissions.stream().sorted().toList();
    }

    private static Set<Permission> withNone(List<Permission> permissions) {
        return permissions.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(permissions);
    }

    private static String literal(List<Permission> permissions) {
        return "{" + String.join(",", permissions.stream().map(Enum::name).toList()) + "}";
    }
}
