package com.mojud.importing;

import com.mojud.catalog.CatalogImport;
import com.mojud.importing.system.ImportResumer;
import com.mojud.importing.system.ImportSweeper;
import com.mojud.inventory.StockLevel;
import com.mojud.inventory.StockQueries;
import com.mojud.inventory.StockService;
import com.mojud.notifications.Notifier;
import com.mojud.shared.audit.AuditTrail;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.tenant.TenantScope;
import com.mojud.tenancy.BusinessRole;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies a committed import (implementation-plan.md 5b), in the background, as its business.
 *
 * <p>Rows of one product are applied together, through the catalog's {@link CatalogImport}, then
 * their stock: OPENING for new variants, an ADJUST to the sheet's figure for existing ones (none
 * when it already matches). Products are grouped into chunks of about 500 rows, each chunk in its
 * own transaction with its rows' results, so a stopped import resumes exactly where it was. A
 * chunk that fails is retried product by product, so one bad product fails only its own rows.
 */
@Component
class ImportRunner implements ImportResumer, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ImportRunner.class);
    static final int CHUNK = 500;

    private record JobInfo(int headerRow, Mapping mapping, UUID committedBy, String fileName) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate batch;
    private final JsonMapper json;
    /** Always a new transaction: the runner may start inside another's after-commit callback (tests). */
    private final TransactionTemplate transactions;
    private final CatalogImport catalog;
    private final StockService stock;
    private final StockQueries stockLevels;
    private final Notifier notifier;
    private final AuditTrail audit;
    private final Clock clock;
    private final ThreadPoolTaskExecutor pool;
    private final Executor executor;

    ImportRunner(DataSource dataSource, JsonMapper json, PlatformTransactionManager transactionManager,
            CatalogImport catalog, StockService stock, StockQueries stockLevels, Notifier notifier, AuditTrail audit,
            Clock clock, @Value("${mojud.imports.background:${mojud.async.background:true}}") boolean background) {
        this.jdbc = JdbcClient.create(dataSource);
        this.batch = new JdbcTemplate(dataSource);
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.catalog = catalog;
        this.stock = stock;
        this.stockLevels = stockLevels;
        this.notifier = notifier;
        this.audit = audit;
        this.clock = clock;
        if (background) {
            // Its own threads: a long import must not hold up the after-commit pool (sign-in codes).
            pool = new ThreadPoolTaskExecutor();
            pool.setThreadNamePrefix("import-");
            pool.setCorePoolSize(2);
            pool.setMaxPoolSize(2);
            pool.setQueueCapacity(100);
            // Over capacity the job stays QUEUED; the sweeper starts it within a few minutes.
            pool.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
            pool.initialize();
            executor = pool;
        } else {
            pool = null;
            executor = Runnable::run;
        }
    }

    /** Starts the import on the runner's threads (inline in tests). */
    void submit(UUID businessId, UUID jobId) {
        executor.execute(() -> run(businessId, jobId));
    }

    /** Does nothing unless the job is queued, or running with a stopped heartbeat. */
    @Override
    public void run(UUID businessId, UUID jobId) {
        try {
            TenantScope.run(businessId, () -> {
                if (claim(jobId)) {
                    process(jobId);
                }
            });
        } catch (RuntimeException e) {
            log.error("Import {} of business {} failed", jobId, businessId, e);
            try {
                TenantScope.run(businessId, () -> transactions.executeWithoutResult(status -> jdbc
                        .sql("update import_jobs set status = 'FAILED', finished_at = ? where id = ?")
                        .params(Timestamp.from(clock.instant()), jobId).update()));
            } catch (RuntimeException again) {
                log.error("Could not mark import {} failed", jobId, again);
            }
        }
    }

    private boolean claim(UUID jobId) {
        Timestamp now = Timestamp.from(clock.instant());
        Integer claimed = transactions.execute(status -> jdbc.sql("""
                        update import_jobs set status = 'RUNNING', started_at = coalesce(started_at, ?), heartbeat_at = ?
                        where id = ? and (status = 'QUEUED' or (status = 'RUNNING' and heartbeat_at < ?))
                        """)
                .params(now, now, jobId, Timestamp.from(clock.instant().minus(ImportSweeper.STALLED)))
                .update());
        return claimed != null && claimed == 1;
    }

    private void process(UUID jobId) {
        JobInfo job = jdbc.sql("select header_row, mapping::text, committed_by, file_name from import_jobs where id = ?")
                .param(jobId)
                .query((rs, n) -> new JobInfo(rs.getInt(1),
                        new Mapping(List.of(json.readValue(rs.getString(2), Mapping.Column[].class))),
                        rs.getObject(3, UUID.class), rs.getString(4)))
                .single();
        List<RowChecker.Parsed> pending = jdbc.sql("""
                        select row_number, cells::text from import_rows
                        where job_id = ? and row_number > ? and action is not null and result is null
                        order by row_number
                        """)
                .params(jobId, job.headerRow())
                .query((rs, n) -> RowChecker.parse(new Sheet.Row(rs.getInt(1),
                        List.of(json.readValue(rs.getString(2), String[].class))), job.mapping()))
                .list();
        for (List<List<RowChecker.Parsed>> chunk : chunks(products(pending))) {
            try {
                transactions.executeWithoutResult(status -> {
                    chunk.forEach(product -> apply(jobId, job, product));
                    beat(jobId);
                });
            } catch (RuntimeException chunkFailed) {
                for (List<RowChecker.Parsed> product : chunk) {
                    try {
                        transactions.executeWithoutResult(status -> apply(jobId, job, product));
                    } catch (RuntimeException e) {
                        transactions.executeWithoutResult(status -> fail(jobId, job, product, e));
                    }
                }
                transactions.executeWithoutResult(status -> beat(jobId));
            }
        }
        transactions.executeWithoutResult(status -> finish(jobId, job));
    }

    /** Rows by product name (ignoring case), in sheet order. */
    private static List<List<RowChecker.Parsed>> products(List<RowChecker.Parsed> rows) {
        Map<String, List<RowChecker.Parsed>> byName = new LinkedHashMap<>();
        rows.forEach(row -> byName.computeIfAbsent(row.name().toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(row));
        return List.copyOf(byName.values());
    }

    /** Whole products, about {@link #CHUNK} rows per chunk. */
    private static List<List<List<RowChecker.Parsed>>> chunks(List<List<RowChecker.Parsed>> products) {
        List<List<List<RowChecker.Parsed>>> chunks = new ArrayList<>();
        List<List<RowChecker.Parsed>> current = new ArrayList<>();
        int rows = 0;
        for (List<RowChecker.Parsed> product : products) {
            current.add(product);
            rows += product.size();
            if (rows >= CHUNK) {
                chunks.add(current);
                current = new ArrayList<>();
                rows = 0;
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    private void apply(UUID jobId, JobInfo job, List<RowChecker.Parsed> rows) {
        RowChecker.Parsed first = rows.get(0);
        List<String> optionNames = job.mapping().options().stream().map(Mapping.Column::optionName)
                .filter(first.options()::containsKey).toList();
        String description = rows.stream().map(RowChecker.Parsed::description).filter(Objects::nonNull).findFirst().orElse(null);
        String category = rows.stream().map(RowChecker.Parsed::category).filter(Objects::nonNull).findFirst().orElse(null);
        List<CatalogImport.VariantRow> variants = rows.stream().map(row -> new CatalogImport.VariantRow(row.sku(), row.options(),
                row.price(), row.costPrice(), row.threshold())).toList();
        List<CatalogImport.Result> results = catalog.apply(new CatalogImport.ProductRows(first.name(), description, category,
                optionNames, variants));

        StockService.Reference reference = StockService.Reference.imported(jobId, job.committedBy());
        Map<UUID, StockLevel> levels = stockLevels.levels(results.stream().map(CatalogImport.Result::variantId).toList());
        List<StockService.Line> opening = new ArrayList<>();
        List<Object[]> outcomes = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            RowChecker.Parsed row = rows.get(i);
            CatalogImport.Result result = results.get(i);
            CatalogImport.Outcome outcome = result.outcome();
            Integer counted = row.stock();
            if (outcome == CatalogImport.Outcome.CREATED) {
                if (counted != null && counted > 0) {
                    opening.add(new StockService.Line(result.variantId(), counted));
                }
            } else if (counted != null && counted != levels.get(result.variantId()).onHand()) {
                stock.count(result.variantId(), counted, reference);
                outcome = CatalogImport.Outcome.UPDATED;
            }
            outcomes.add(new Object[] {outcome.name(), jobId, row.rowNumber()});
        }
        stock.opening(opening, reference);
        batch.batchUpdate("update import_rows set result = ? where job_id = ? and row_number = ?", outcomes);
    }

    /** The product's rows failed: each gets the reason, in the same words as the preview's errors. */
    private void fail(UUID jobId, JobInfo job, List<RowChecker.Parsed> rows, RuntimeException cause) {
        Mapping.Column name = job.mapping().of(ImportField.NAME).orElseThrow();
        RowChecker.RowError error;
        if (cause instanceof CatalogImport.Rejected rejected) {
            error = RowChecker.error(name, rejected.code(), rejected.args().toArray());
        } else if (cause instanceof ApiException api && api.code() == ErrorCode.VALIDATION_FAILED
                && api.details().get("errors") instanceof List<?> problems && !problems.isEmpty()
                && problems.get(0) instanceof ApiException.FieldProblem problem) {
            error = RowChecker.error(name, problem.messageKey());
        } else if (cause instanceof ApiException api) {
            error = RowChecker.error(name, "error." + api.code().code());
        } else {
            log.error("Import {}: rows {} could not be saved", jobId, rows.stream().map(RowChecker.Parsed::rowNumber).toList(), cause);
            error = RowChecker.error(name, "save_failed");
        }
        String errors = json.writeValueAsString(List.of(error));
        batch.batchUpdate("update import_rows set result = 'FAILED', errors = ?::jsonb where job_id = ? and row_number = ?",
                rows.stream().map(row -> new Object[] {errors, jobId, row.rowNumber()}).toList());
    }

    private void beat(UUID jobId) {
        jdbc.sql("update import_jobs set heartbeat_at = ? where id = ?").params(Timestamp.from(clock.instant()), jobId).update();
    }

    private void finish(UUID jobId, JobInfo job) {
        ImportService.Progress counts = ImportService.progress(jdbc, jobId, job.headerRow());
        jdbc.sql("update import_jobs set status = 'DONE', finished_at = ?, heartbeat_at = ? where id = ?")
                .params(Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), jobId).update();
        UUID business = TenantScope.current().orElseThrow();
        audit.record(business, job.committedBy(), "import.committed", "import_job", jobId, "file", job.fileName(),
                "created", counts.created(), "updated", counts.updated(), "unchanged", counts.unchanged(),
                "failed", counts.failed(), "skipped", counts.skipped());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("importId", jobId.toString());
        payload.put("args", List.of(job.fileName(), counts.created() + counts.updated() + counts.unchanged(),
                counts.failed() + counts.skipped()));
        notifier.notify("import.finished", BusinessRole.MANAGER, payload, null);
    }

    @Override
    public void destroy() {
        if (pool != null) {
            pool.shutdown();
        }
    }
}
