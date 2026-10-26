package com.mojud.importing;

import com.mojud.catalog.CatalogLimits;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Which column holds which field. Columns not listed are ignored. */
record Mapping(List<Column> columns) {

    /** {@code optionName} only for {@link ImportField#OPTION}. */
    record Column(int index, ImportField field, String optionName) {
    }

    /** Headers are looked for in this many rows from the top (titles and merged cells come first). */
    static final int HEADER_SEARCH_ROWS = 10;

    Optional<Column> of(ImportField field) {
        return columns.stream().filter(column -> column.field() == field).findFirst();
    }

    List<Column> options() {
        return columns.stream().filter(column -> column.field() == ImportField.OPTION).toList();
    }

    /**
     * The header row: the first of the top rows naming at least two known fields, so a title
     * ("Eid stock list 2026") or a merged group heading above the headers is skipped. Otherwise
     * the first row.
     */
    static Sheet.Row findHeader(List<Sheet.Row> rows) {
        return rows.stream().limit(HEADER_SEARCH_ROWS)
                .filter(row -> guess(row).columns().stream().filter(column -> column.field() != ImportField.OPTION).count() >= 2)
                .findFirst()
                .orElse(rows.get(0));
    }

    /** Columns recognised from their headers; each field once (the first column), at most 3 options. */
    static Mapping guess(Sheet.Row header) {
        List<Column> columns = new ArrayList<>();
        Set<ImportField> seen = new HashSet<>();
        Set<String> optionNames = new HashSet<>();
        for (int i = 0; i < header.cells().size(); i++) {
            String text = header.cells().get(i);
            Optional<ImportField> field = ImportField.recognise(text);
            if (field.isEmpty()) {
                continue;
            }
            if (field.get() == ImportField.OPTION) {
                String name = ImportField.optionName(text);
                if (name.length() <= CatalogLimits.OPTION_TEXT && optionNames.size() < CatalogLimits.MAX_OPTIONS
                        && optionNames.add(name.toLowerCase(Locale.ROOT))) {
                    columns.add(new Column(i, ImportField.OPTION, name));
                }
            } else if (seen.add(field.get())) {
                columns.add(new Column(i, field.get(), null));
            }
        }
        return new Mapping(List.copyOf(columns));
    }
}
