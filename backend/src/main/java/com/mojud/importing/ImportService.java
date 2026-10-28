package com.mojud.importing;

import com.mojud.billing.Entitlements;
import com.mojud.billing.Metric;
import com.mojud.catalog.CatalogLimits;
import com.mojud.catalog.CatalogLookup;
import com.mojud.shared.async.AfterCommitTasks;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.id.Ids;
import com.mojud.shared.ratelimit.RateLimiter;
import com.mojud.shared.text.Text;
import com.mojud.tenancy.CurrentBusiness;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Product imports up to the preview (implementation-plan.md 5a). The upload is read at once and its
 * rows kept; the columns are guessed from the headers and can be changed; every change re-checks
 * all rows, so the totals and errors are always those of the current mapping.
 */
@Service
class ImportService {

    static final String MAPPING = "MAPPING";
    static final int PREVIEW_ROWS = 20;
    private static final int SAMPLES = 3;

    record ColumnView(int index, String letter, String header, List<String> samples, ImportField field,
            String optionName) {
    }

    record Totals(int rows, int valid, int invalid, int create, int update) {
    }

    /**
     * Once committed: {@code toImport} rows were valid at commit, {@code done} of them are applied
     * (created, updated, unchanged or failed); {@code skipped} rows had errors and were left out.
     */
    record Progress(int toImport, int done, int created, int updated, int unchanged, int failed, int skipped) {
    }

    /** {@code progress} is null until the import is committed. */
    record Job(UUID id, String fileName, Sheet.FileType fileType, String status, int headerRow, List<ColumnView> columns,
            Totals totals, List<String> newCategories, Progress progress, Instant createdAt, Instant finishedAt) {
    }

    /** {@code field} is the field's name in the reader's language (an option's own name for options). */
    record ErrorView(String column, String field, String message) {
    }

    record PreviewRow(int row, RowChecker.Action action, String name, String sku, Long price, Long costPrice, Integer stock,
            Integer lowStockThreshold, String category, String description, Map<String, String> options,
            List<ErrorView> errors) {
    }

    record Preview(Totals totals, List<String> newCategories, List<PreviewRow> rows) {
    }

    private record JobRow(UUID id, String fileName, Sheet.FileType fileType, String status, int headerRow, int columnCount,
            Mapping mapping, Totals totals, List<String> newCategories, Instant createdAt, Instant finishedAt) {
    }

    private final SheetReader reader;
    private final Entitlements entitlements;
    private final ImportRunner runner;
    private final AfterCommitTasks afterCommit;
    private final CatalogLookup catalog;
    private final JdbcClient jdbc;
    private final JdbcTemplate batch;
    private final JsonMapper json;
    private final MessageSource messages;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final RateLimiter importsPerBusiness;

    ImportService(SheetReader reader, ImportRunner runner, AfterCommitTasks afterCommit, CatalogLookup catalog, DataSource dataSource, JsonMapper json, MessageSource messages,
            TransactionTemplate transactions, Clock clock, @Value("${mojud.imports.per-business.max}") int perBusinessMax,
            @Value("${mojud.imports.per-business.window}") Duration perBusinessWindow, Entitlements entitlements) {
        this.entitlements = entitlements;
        this.reader = reader;
        this.runner = runner;
        this.afterCommit = afterCommit;
        this.catalog = catalog;
        this.jdbc = JdbcClient.create(dataSource);
        this.batch = new JdbcTemplate(dataSource);
        this.json = json;
        this.messages = messages;
        this.transactions = transactions;
        this.clock = clock;
        this.importsPerBusiness = new RateLimiter("imports-per-business", perBusinessMax, perBusinessWindow);
    }

    /** Reads the file (before any transaction: it may take a moment), then stores and checks it. */
    Job create(String fileName, byte[] content) {
        CurrentBusiness me = CurrentBusiness.require();
        importsPerBusiness.acquire(me.businessId().toString());
        Sheet sheet = reader.read(content);
        Sheet.Row header = Mapping.findHeader(sheet.rows());
        return transactions.execute(status -> store(me, fileName, sheet, header.number(), Mapping.guess(header)));
    }

    private Job store(CurrentBusiness me, String fileName, Sheet sheet, int headerRow, Mapping mapping) {
        UUID id = Ids.newId();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql("""
                        insert into import_jobs (id, business_id, file_name, file_type, status, header_row, column_count,
                                                 mapping, created_by, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                        """)
                .params(id, me.businessId(), fileName, sheet.type().name(), MAPPING, headerRow, sheet.columnCount(),
                        json.writeValueAsString(mapping.columns()), me.userId(), now, now)
                .update();
        batch.batchUpdate("insert into import_rows (business_id, job_id, row_number, cells) values (?, ?, ?, ?::jsonb)",
                sheet.rows().stream().map(row -> new Object[] {me.businessId(), id, row.number(),
                        json.writeValueAsString(row.cells())}).toList());
        check(id, headerRow, mapping);
        return view(load(id, false));
    }

    @Transactional(readOnly = true)
    Job job(UUID id) {
        return view(load(id, false));
    }

    record ColumnRequest(int index, ImportField field, String optionName) {
    }

    /** Columns not listed are ignored. A changed {@code headerRow} also changes which rows are data. */
    @Transactional
    Job remap(UUID id, Integer headerRow, List<ColumnRequest> columns) {
        JobRow job = load(id, true);
        if (!MAPPING.equals(job.status())) {
            throw new ApiException(ErrorCode.IMPORT_NOT_EDITABLE);
        }
        int header = headerRow == null ? job.headerRow() : headerRow;
        List<String> headers = header == job.headerRow() ? headers(job) : cells(id, header)
                .orElseThrow(() -> ApiException.invalidField("headerRow", "field.invalid_value"));
        Mapping mapping = mapping(columns, job.columnCount(), headers);
        jdbc.sql("update import_jobs set header_row = ?, mapping = ?::jsonb, updated_at = ? where id = ?")
                .params(header, json.writeValueAsString(mapping.columns()), Timestamp.from(clock.instant()), id)
                .update();
        check(id, header, mapping);
        return view(load(id, false));
    }

    /**
     * Checks the rows once more (the catalog may have changed since the preview) and queues the
     * valid ones; the import starts once this commits. Rows with errors are left out.
     */
    @Transactional
    Job commit(UUID id) {
        CurrentBusiness me = CurrentBusiness.require();
        JobRow job = load(id, true);
        if (!MAPPING.equals(job.status())) {
            throw new ApiException(ErrorCode.IMPORT_NOT_EDITABLE);
        }
        check(id, job.headerRow(), job.mapping());
        int valid = load(id, false).totals().valid();
        if (valid == 0) {
            throw new ApiException(ErrorCode.IMPORT_NOTHING_VALID);
        }
        entitlements.requireWithin(Metric.IMPORT_ROWS, valid);
        jdbc.sql("update import_jobs set status = 'QUEUED', committed_by = ?, queued_at = ?, updated_at = ? where id = ?")
                .params(me.userId(), Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), id)
                .update();
        afterCommit.run(() -> runner.submit(me.businessId(), id));
        return view(load(id, false));
    }

    /**
     * Every row with a problem (left out, or failed when applied): its row number, the problems,
     * then its cells under the sheet's own headers, so it can be fixed and uploaded again. UTF-8
     * with a byte-order mark, so Excel shows Bangla correctly.
     */
    @Transactional(readOnly = true)
    byte[] errorsCsv(UUID id) {
        JobRow job = load(id, false);
        Locale locale = LocaleContextHolder.getLocale();
        StringBuilder csv = new StringBuilder("﻿");
        List<String> header = new ArrayList<>(List.of(messages.getMessage("import.errors.row", null, locale),
                messages.getMessage("import.errors.problems", null, locale)));
        header.addAll(pad(headers(job), job.columnCount()));
        csvLine(csv, header);
        jdbc.sql("""
                        select row_number, cells::text, errors::text from import_rows
                        where job_id = ? and row_number > ? and errors <> '[]'::jsonb
                        order by row_number
                        """)
                .params(id, job.headerRow())
                .query((rs, n) -> {
                    List<String> line = new ArrayList<>();
                    line.add(Integer.toString(rs.getInt(1)));
                    line.add(String.join("; ", Arrays.stream(json.readValue(rs.getString(3), RowChecker.RowError[].class))
                            .map(error -> errorView(error, locale))
                            .map(error -> error.column() + " " + error.field() + " " + error.message())
                            .toList()));
                    line.addAll(pad(List.of(json.readValue(rs.getString(2), String[].class)), job.columnCount()));
                    return line;
                })
                .list()
                .forEach(line -> csvLine(csv, line));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> pad(List<String> cells, int size) {
        List<String> padded = new ArrayList<>(cells);
        while (padded.size() < size) {
            padded.add("");
        }
        return padded;
    }

    /** RFC 4180: quoted when needed, CRLF line ends. */
    private static void csvLine(StringBuilder csv, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            String cell = cells.get(i);
            if (i > 0) {
                csv.append(',');
            }
            if (cell.contains(",") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r")) {
                csv.append('"').append(cell.replace("\"", "\"\"")).append('"');
            } else {
                csv.append(cell);
            }
        }
        csv.append("\r\n");
    }

    /** Counts of the committed rows by result. */
    static Progress progress(JdbcClient jdbc, UUID id, int headerRow) {
        return jdbc.sql("""
                        select count(*) filter (where action is not null),
                               count(*) filter (where result is not null),
                               count(*) filter (where result = 'CREATED'),
                               count(*) filter (where result = 'UPDATED'),
                               count(*) filter (where result = 'UNCHANGED'),
                               count(*) filter (where result = 'FAILED'),
                               count(*) filter (where action is null)
                        from import_rows where job_id = ? and row_number > ?
                        """)
                .params(id, headerRow)
                .query((rs, n) -> new Progress(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                        rs.getInt(6), rs.getInt(7)))
                .single();
    }

    /** The first rows (or the first rows with errors), as the commit would read them. */
    @Transactional(readOnly = true)
    Preview preview(UUID id, boolean invalidOnly) {
        JobRow job = load(id, false);
        Locale locale = LocaleContextHolder.getLocale();
        List<PreviewRow> rows = jdbc.sql("""
                        select row_number, cells::text, action, errors::text from import_rows
                        where job_id = ? and row_number > ? and (not ? or errors <> '[]'::jsonb)
                        order by row_number limit ?
                        """)
                .params(id, job.headerRow(), invalidOnly, PREVIEW_ROWS)
                .query((rs, n) -> {
                    Sheet.Row row = new Sheet.Row(rs.getInt(1), List.of(json.readValue(rs.getString(2), String[].class)));
                    RowChecker.Parsed parsed = RowChecker.parse(row, job.mapping());
                    String action = rs.getString(3);
                    List<ErrorView> errors = Arrays.stream(json.readValue(rs.getString(4), RowChecker.RowError[].class))
                            .map(error -> errorView(error, locale)).toList();
                    return new PreviewRow(row.number(), action == null ? null : RowChecker.Action.valueOf(action),
                            parsed.name(), parsed.sku(), parsed.price(), parsed.costPrice(), parsed.stock(),
                            parsed.threshold(), parsed.category(), parsed.description(), parsed.options(), errors);
                })
                .list();
        return new Preview(job.totals(), job.newCategories(), rows);
    }

    /** Checks every data row against the mapping and the catalog, and stores the results. */
    private void check(UUID id, int headerRow, Mapping mapping) {
        List<Sheet.Row> rows = jdbc.sql("select row_number, cells::text from import_rows where job_id = ? and row_number > ? order by row_number")
                .params(id, headerRow)
                .query((rs, n) -> new Sheet.Row(rs.getInt(1), List.of(json.readValue(rs.getString(2), String[].class))))
                .list();
        RowChecker.Outcome outcome = RowChecker.check(rows, mapping, catalog);
        jdbc.sql("update import_rows set action = null, errors = '[]'::jsonb where job_id = ? and row_number <= ?")
                .params(id, headerRow).update();
        batch.batchUpdate("update import_rows set action = ?, errors = ?::jsonb where job_id = ? and row_number = ?",
                outcome.rows().stream().map(row -> new Object[] {row.action() == null ? null : row.action().name(),
                        json.writeValueAsString(row.errors()), id, row.rowNumber()}).toList());
        jdbc.sql("""
                        update import_jobs set total_rows = ?, valid_rows = ?, create_rows = ?, update_rows = ?,
                                               new_categories = ?::jsonb
                        where id = ?
                        """)
                .params(outcome.rows().size(), (int) outcome.valid(), (int) outcome.count(RowChecker.Action.CREATE),
                        (int) outcome.count(RowChecker.Action.UPDATE), json.writeValueAsString(outcome.newCategories()), id)
                .update();
    }

    /** Validates a mapping from the client; option names default to their column's header. */
    private static Mapping mapping(List<ColumnRequest> requested, int columnCount, List<String> headers) {
        List<Mapping.Column> columns = new ArrayList<>();
        Set<Integer> indexes = new HashSet<>();
        Set<ImportField> fields = new HashSet<>();
        Set<String> optionNames = new HashSet<>();
        for (ColumnRequest column : requested) {
            if (column.index() < 0 || column.index() >= columnCount || !indexes.add(column.index())) {
                throw ApiException.invalidField("columns", "field.mapping_column");
            }
            if (column.field().single() && !fields.add(column.field())) {
                throw ApiException.invalidField("columns", "field.mapping_field_twice");
            }
            String optionName = null;
            if (column.field() == ImportField.OPTION) {
                String given = column.optionName() == null || column.optionName().isBlank()
                        ? ImportField.optionName(column.index() < headers.size() ? headers.get(column.index()) : "")
                        : column.optionName();
                optionName = Text.cleanName(given, "columns", CatalogLimits.OPTION_TEXT);
                if (!optionNames.add(optionName.toLowerCase(Locale.ROOT))) {
                    throw ApiException.invalidField("columns", "field.option_duplicate");
                }
                if (optionNames.size() > CatalogLimits.MAX_OPTIONS) {
                    throw ApiException.invalidField("columns", "field.too_many_options");
                }
            }
            columns.add(new Mapping.Column(column.index(), column.field(), optionName));
        }
        if (!fields.containsAll(ImportField.REQUIRED)) {
            throw ApiException.invalidField("columns", "field.mapping_required");
        }
        columns.sort((a, b) -> Integer.compare(a.index(), b.index()));
        return new Mapping(List.copyOf(columns));
    }

    private Job view(JobRow job) {
        List<String> headers = headers(job);
        List<List<String>> data = jdbc.sql("select cells::text from import_rows where job_id = ? and row_number > ? order by row_number limit 50")
                .params(job.id(), job.headerRow())
                .query((rs, n) -> List.of(json.readValue(rs.getString(1), String[].class)))
                .list();
        List<ColumnView> columns = new ArrayList<>();
        for (int i = 0; i < job.columnCount(); i++) {
            int index = i;
            List<String> samples = data.stream().map(cells -> index < cells.size() ? cells.get(index) : "")
                    .filter(text -> !text.isEmpty()).limit(SAMPLES).toList();
            Mapping.Column mapped = job.mapping().columns().stream().filter(c -> c.index() == index).findFirst().orElse(null);
            columns.add(new ColumnView(index, letter(index), index < headers.size() ? headers.get(index) : "", samples,
                    mapped == null ? null : mapped.field(), mapped == null ? null : mapped.optionName()));
        }
        Progress progress = MAPPING.equals(job.status()) ? null : progress(jdbc, job.id(), job.headerRow());
        return new Job(job.id(), job.fileName(), job.fileType(), job.status(), job.headerRow(), columns, job.totals(),
                job.newCategories(), progress, job.createdAt(), job.finishedAt());
    }

    private List<String> headers(JobRow job) {
        return cells(job.id(), job.headerRow()).orElse(List.of());
    }

    private Optional<List<String>> cells(UUID id, int rowNumber) {
        return jdbc.sql("select cells::text from import_rows where job_id = ? and row_number = ?")
                .params(id, rowNumber)
                .query((rs, n) -> List.of(json.readValue(rs.getString(1), String[].class)))
                .optional();
    }

    /** Plain SQL: the business is passed by hand (row-level security also applies). */
    private JobRow load(UUID id, boolean lock) {
        return jdbc.sql("""
                        select id, file_name, file_type, status, header_row, column_count, mapping::text, total_rows,
                               valid_rows, create_rows, update_rows, new_categories::text, created_at, finished_at
                        from import_jobs where id = ? and business_id = ?
                        """ + (lock ? " for update" : ""))
                .params(id, CurrentBusiness.require().businessId())
                .query((rs, n) -> {
                    int total = rs.getInt(8);
                    int valid = rs.getInt(9);
                    return new JobRow(rs.getObject(1, UUID.class), rs.getString(2), Sheet.FileType.valueOf(rs.getString(3)),
                            rs.getString(4), rs.getInt(5), rs.getInt(6),
                            new Mapping(List.of(json.readValue(rs.getString(7), Mapping.Column[].class))),
                            new Totals(total, valid, total - valid, rs.getInt(10), rs.getInt(11)),
                            List.of(json.readValue(rs.getString(12), String[].class)), rs.getTimestamp(13).toInstant(),
                            rs.getTimestamp(14) == null ? null : rs.getTimestamp(14).toInstant());
                })
                .optional()
                .orElseThrow(ApiException::notFound);
    }

    private ErrorView errorView(RowChecker.RowError error, Locale locale) {
        String field = error.field() == ImportField.OPTION
                ? error.optionName()
                : messages.getMessage("import.field." + error.field().name(), null, error.field().name(), locale);
        // Failures found when applying a row reuse the app's own messages (error.* and field.*).
        String key = error.code().startsWith("error.") || error.code().startsWith("field.") ? error.code()
                : "import.error." + error.code();
        String message = messages.getMessage(key, error.args().toArray(), error.code(), locale);
        return new ErrorView(letter(error.column()), field, message);
    }

    /** Spreadsheet column letters: 0 is A, 25 is Z, 26 is AA. */
    static String letter(int index) {
        StringBuilder letters = new StringBuilder();
        for (int i = index; i >= 0; i = i / 26 - 1) {
            letters.insert(0, (char) ('A' + i % 26));
        }
        return letters.toString();
    }
}
