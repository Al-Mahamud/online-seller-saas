package com.mojud.importing;

import java.util.List;

/** A read file: its non-blank rows, cell text by column. */
record Sheet(FileType type, List<Row> rows, int columnCount) {

    enum FileType {
        CSV, XLSX
    }

    /** {@code number} as in the sheet: 1 is the first row. */
    record Row(int number, List<String> cells) {

        String cell(int index) {
            return index < cells.size() ? cells.get(index) : "";
        }
    }
}
