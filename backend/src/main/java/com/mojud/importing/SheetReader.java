package com.mojud.importing;

import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.springframework.stereotype.Component;

/**
 * Reads an uploaded sheet: {@code .xlsx} (the first sheet, streamed) or {@code .csv} (UTF-8, with
 * or without a byte-order mark; comma, semicolon or tab separated). The content decides the type,
 * not the file name. Cell text is Unicode-normalised (NFC) and trimmed; blank rows are dropped.
 */
@Component
class SheetReader {

    static final int MAX_ROWS = 10_000;
    static final int MAX_COLUMNS = 100;
    /** Longer cells are cut; nothing the catalog accepts is this long. */
    static final int MAX_CELL = 10_000;
    /** An .xlsx is a zip; this bounds how much it may unpack to (a "zip bomb" guard). */
    static final long MAX_UNPACKED_BYTES = 200L * 1024 * 1024;

    private static final byte[] OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final byte[] ZIP = {'P', 'K', 3, 4};
    private static final Pattern EDGE_SPACES = Pattern.compile("^[\\s\\p{Z}]+|[\\s\\p{Z}]+$");

    Sheet read(byte[] content) {
        if (content.length == 0) {
            throw new ApiException(ErrorCode.IMPORT_EMPTY);
        }
        if (startsWith(content, OLE2)) {
            // An old .xls (or another Office binary file).
            throw new ApiException(ErrorCode.IMPORT_UNREADABLE);
        }
        Sheet sheet = startsWith(content, ZIP) ? readXlsx(content) : readCsv(content);
        if (sheet.rows().isEmpty()) {
            throw new ApiException(ErrorCode.IMPORT_EMPTY);
        }
        return sheet;
    }

    // .xlsx

    private Sheet readXlsx(byte[] content) {
        checkUnpackedSize(content);
        Rows rows = new Rows();
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(content));
                Stream<org.dhatim.fastexcel.reader.Row> stream = workbook.getFirstSheet().openStream()) {
            Iterator<org.dhatim.fastexcel.reader.Row> iterator = stream.iterator();
            while (iterator.hasNext()) {
                org.dhatim.fastexcel.reader.Row row = iterator.next();
                List<String> cells = new ArrayList<>();
                for (int i = 0; i < row.getCellCount(); i++) {
                    cells.add(text(row.getCell(i)));
                }
                rows.add(row.getRowNum(), cells);
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException api) {
                throw api;
            }
            throw new ApiException(ErrorCode.IMPORT_UNREADABLE);
        }
        return rows.toSheet(Sheet.FileType.XLSX);
    }

    /**
     * Unpacks without keeping anything, to refuse files that would grow too large. Uses
     * commons-compress, which (unlike java.util.zip) reads the stored entries with data descriptors
     * that some spreadsheet tools write.
     */
    private static void checkUnpackedSize(byte[] content) {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipArchiveInputStream zip = new ZipArchiveInputStream(new ByteArrayInputStream(content), "UTF-8", true, true)) {
            while (zip.getNextEntry() != null) {
                int read;
                while ((read = zip.read(buffer)) > 0) {
                    total += read;
                    if (total > MAX_UNPACKED_BYTES) {
                        throw new ApiException(ErrorCode.IMPORT_TOO_BIG, MAX_ROWS, MAX_COLUMNS);
                    }
                }
            }
        } catch (IOException e) {
            throw new ApiException(ErrorCode.IMPORT_UNREADABLE);
        }
    }

    /** What the seller sees in the cell. Numbers without float noise: 1200, not 1199.9999999999998. */
    private static String text(Cell cell) {
        if (cell == null || cell.getRawValue() == null) {
            return "";
        }
        String raw = cell.getRawValue();
        return switch (cell.getType()) {
            case NUMBER -> number(raw);
            case BOOLEAN -> "1".equals(raw) || "true".equalsIgnoreCase(raw) ? "TRUE" : "FALSE";
            case FORMULA -> looksNumeric(raw) ? number(raw) : raw;
            case EMPTY -> "";
            default -> raw;
        };
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        return content.length >= signature.length && Arrays.equals(content, 0, signature.length, signature, 0, signature.length);
    }

    private static boolean looksNumeric(String raw) {
        return raw.matches("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");
    }

    private static String number(String raw) {
        try {
            // Excel shows 15 significant digits; the file may hold 17.
            return new BigDecimal(raw).round(new MathContext(15)).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    // .csv

    private Sheet readCsv(byte[] content) {
        String text = decodeUtf8(content);
        if (text.indexOf('\0') >= 0) {
            // A binary file, not text.
            throw new ApiException(ErrorCode.IMPORT_UNREADABLE);
        }
        char separator = separator(text);
        Rows rows = new Rows();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int rowNumber = 1;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.toString().isBlank()) {
                cell.setLength(0);
                quoted = true;
            } else if (c == separator) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                cells.add(cell.toString());
                cell.setLength(0);
                rows.add(rowNumber++, cells);
                cells = new ArrayList<>();
            } else {
                cell.append(c);
            }
            i++;
        }
        if (cell.length() > 0 || !cells.isEmpty()) {
            cells.add(cell.toString());
            rows.add(rowNumber, cells);
        }
        return rows.toSheet(Sheet.FileType.CSV);
    }

    /** Strict UTF-8 (a byte-order mark is dropped). Anything else gets "save it as CSV UTF-8". */
    private static String decodeUtf8(byte[] content) {
        int start = content.length >= 3 && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB
                && (content[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content, start, content.length - start))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ApiException(ErrorCode.IMPORT_UNREADABLE);
        }
    }

    /** The most common of comma, semicolon and tab outside quotes on the first line; comma on a tie. */
    private static char separator(String text) {
        int comma = 0;
        int semicolon = 0;
        int tab = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && (c == '\n' || c == '\r')) {
                break;
            } else if (!quoted && c == ',') {
                comma++;
            } else if (!quoted && c == ';') {
                semicolon++;
            } else if (!quoted && c == '\t') {
                tab++;
            }
        }
        if (semicolon > comma && semicolon >= tab) {
            return ';';
        }
        return tab > comma ? '\t' : ',';
    }

    /** Collects non-blank rows, tidying cells and enforcing the size limits. */
    private static final class Rows {

        private final List<Sheet.Row> rows = new ArrayList<>();
        private int columnCount;

        void add(int number, List<String> raw) {
            List<String> cells = new ArrayList<>(raw.size());
            int last = -1;
            for (int i = 0; i < raw.size(); i++) {
                String cell = tidy(raw.get(i));
                cells.add(cell);
                if (!cell.isEmpty()) {
                    last = i;
                }
            }
            if (last < 0) {
                return;
            }
            if (last >= MAX_COLUMNS || rows.size() >= MAX_ROWS) {
                throw new ApiException(ErrorCode.IMPORT_TOO_BIG, MAX_ROWS, MAX_COLUMNS);
            }
            List<String> kept = List.copyOf(cells.subList(0, last + 1));
            columnCount = Math.max(columnCount, kept.size());
            rows.add(new Sheet.Row(number, kept));
        }

        Sheet toSheet(Sheet.FileType type) {
            return new Sheet(type, List.copyOf(rows), columnCount);
        }

        private static String tidy(String cell) {
            if (cell == null || cell.isEmpty()) {
                return "";
            }
            String text = EDGE_SPACES.matcher(Normalizer.normalize(cell, Normalizer.Form.NFC)).replaceAll("");
            return text.length() > MAX_CELL ? text.substring(0, MAX_CELL) : text;
        }
    }
}
