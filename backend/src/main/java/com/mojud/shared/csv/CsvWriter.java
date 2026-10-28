package com.mojud.shared.csv;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CSV for spreadsheets (RFC 4180): UTF-8 with a byte-order mark, so Excel shows Bangla correctly;
 * CRLF line ends; quoting when needed. Text that a spreadsheet would read as a formula (=, +, -, @
 * and a few control characters first) gets a leading apostrophe: an exported customer name must
 * not run in the seller's Excel. Numbers are written as they are.
 */
public final class CsvWriter implements AutoCloseable {

    private final Writer out;
    private boolean lineStarted;

    public CsvWriter(OutputStream stream) {
        this.out = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
        write("﻿");
    }

    /** A whole row of text cells. */
    public CsvWriter row(List<String> cells) {
        cells.forEach(this::text);
        return end();
    }

    public CsvWriter text(String value) {
        String cell = value == null ? "" : value;
        if (!cell.isEmpty() && "=+-@\t\r".indexOf(cell.charAt(0)) >= 0) {
            cell = "'" + cell;
        }
        return cell(cell);
    }

    public CsvWriter number(long value) {
        return cell(Long.toString(value));
    }

    /** Poisha as taka with two decimals ("1500.00"), the way spreadsheets read money. */
    public CsvWriter taka(long poisha) {
        String sign = poisha < 0 ? "-" : "";
        long abs = Math.abs(poisha);
        return cell(sign + abs / 100 + "." + (abs % 100 < 10 ? "0" : "") + abs % 100);
    }

    /** A cell that may be empty (null), written as text otherwise. */
    public CsvWriter optionalTaka(Long poisha) {
        return poisha == null ? cell("") : taka(poisha);
    }

    public CsvWriter end() {
        write("\r\n");
        lineStarted = false;
        return this;
    }

    @Override
    public void close() {
        try {
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private CsvWriter cell(String cell) {
        if (lineStarted) {
            write(",");
        }
        lineStarted = true;
        if (cell.contains(",") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r")) {
            write("\"" + cell.replace("\"", "\"\"") + "\"");
        } else {
            write(cell);
        }
        return this;
    }

    private void write(String text) {
        try {
            out.write(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
