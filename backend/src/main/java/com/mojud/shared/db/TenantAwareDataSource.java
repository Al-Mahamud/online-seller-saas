package com.mojud.shared.db;

import com.mojud.shared.tenant.TenantScope;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Wraps the application's connection pool: every connection handed out first gets
 * {@code app.business_id} set to the current {@link TenantScope}, or to {@code ''} when there is
 * none. Setting it on every checkout means a pooled connection never keeps the previous user's
 * business.
 */
class TenantAwareDataSource extends DelegatingDataSource {

    private static final String SET_TENANT = "select set_config('app.business_id', ?, false)";

    TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return withTenant(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return withTenant(super.getConnection(username, password));
    }

    private static Connection withTenant(Connection connection) throws SQLException {
        String businessId = TenantScope.current().map(UUID::toString).orElse("");
        try (PreparedStatement statement = connection.prepareStatement(SET_TENANT)) {
            statement.setString(1, businessId);
            statement.execute();
            return connection;
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }
}
