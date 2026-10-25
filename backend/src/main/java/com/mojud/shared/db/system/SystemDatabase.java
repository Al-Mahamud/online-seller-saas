package com.mojud.shared.db.system;

import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Database access as {@code mojud_system}, which bypasses row-level security and so sees every
 * business. For jobs that scan all businesses, payment-gateway callbacks and platform admin only.
 * Only classes in a {@code ..system..} package may use it (SystemDatabaseAccessTests).
 *
 * <p>Work for one business should not run here: open a {@code TenantScope} and use the normal
 * repositories, so row-level security still applies.
 */
public class SystemDatabase {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public SystemDatabase(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    /** Runs {@code work} in one transaction on the system pool. */
    public <T> T inTransaction(TransactionCallback<T> work) {
        return transactions.execute(work);
    }
}
