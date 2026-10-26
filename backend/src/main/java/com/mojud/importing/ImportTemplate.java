package com.mojud.importing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * The sheet sellers can fill in: headers in the reader's language (all recognised on upload) and
 * three example rows, two of them variants of one product.
 */
@Component
class ImportTemplate {

    private static final List<String> HEADERS = List.of("NAME", "SKU", "PRICE", "COST_PRICE", "STOCK", "CATEGORY",
            "size", "color", "DESCRIPTION", "LOW_STOCK_THRESHOLD");
    private static final List<List<Object>> EXAMPLES = List.of(
            List.of("Cotton Kurti", "KURTI-S-BLK", 1500, 900, 10, "Women > Kurti", "S", "Black", "Soft cotton, hand-stitched", ""),
            List.of("Cotton Kurti", "KURTI-M-BLK", 1500, 900, 8, "Women > Kurti", "M", "Black", "", ""),
            List.of("Leather Wallet", "WALLET-01", 950, 600, 25, "Accessories", "", "", "", 5));

    private final MessageSource messages;

    ImportTemplate(MessageSource messages) {
        this.messages = messages;
    }

    byte[] build(Locale locale) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            Workbook workbook = new Workbook(out, "Mojud", "1.0");
            Worksheet sheet = workbook.newWorksheet(messages.getMessage("import.template.sheet", null, locale));
            for (int column = 0; column < HEADERS.size(); column++) {
                sheet.value(0, column, messages.getMessage("import.template." + HEADERS.get(column), null, locale));
                sheet.style(0, column).bold().fillColor("E6F2EC").set();
                sheet.width(column, column == 0 || column == 8 ? 28 : 16);
            }
            for (int row = 0; row < EXAMPLES.size(); row++) {
                List<Object> values = EXAMPLES.get(row);
                for (int column = 0; column < values.size(); column++) {
                    Object value = values.get(column);
                    if (value instanceof Number number) {
                        sheet.value(row + 1, column, number);
                    } else if (!value.toString().isEmpty()) {
                        sheet.value(row + 1, column, value.toString());
                    }
                }
            }
            sheet.freezePane(0, 1);
            workbook.finish();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
