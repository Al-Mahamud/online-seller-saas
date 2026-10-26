package com.mojud.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mojud.shared.error.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.junit.jupiter.api.Test;

class SheetReaderTest {

    private final SheetReader reader = new SheetReader();

    @Test
    void csvWithByteOrderMarkSemicolonsQuotesAndBangla() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        String text = "নাম;দাম\r\n\r\nসুতি কুর্তি;১,২০০\r\n\"Kurti; \"\"long\"\"\nline\";  1200  \r\n";
        byte[] content = concat(bom, text.getBytes(StandardCharsets.UTF_8));

        Sheet sheet = reader.read(content);

        assertThat(sheet.type()).isEqualTo(Sheet.FileType.CSV);
        assertThat(sheet.rows()).containsExactly(new Sheet.Row(1, List.of("নাম", "দাম")),
                new Sheet.Row(3, List.of("সুতি কুর্তি", "১,২০০")),
                new Sheet.Row(4, List.of("Kurti; \"long\"\nline", "1200")));
    }

    @Test
    void banglaTextIsNormalisedSoTheSameWordMatches() {
        // The letter rra typed as one code point and as two (dda + nukta) reads the same.
        Sheet one = reader.read("\u09DC,x\n".getBytes(StandardCharsets.UTF_8));
        Sheet two = reader.read("\u09A1\u09BC,x\n".getBytes(StandardCharsets.UTF_8));

        assertThat(one.rows().get(0).cell(0)).isEqualTo(two.rows().get(0).cell(0));
    }

    @Test
    void xlsxNumbersReadAsTheSellerSeesThem() throws IOException {
        byte[] content = xlsx(sheet -> {
            sheet.value(0, 0, "Name");
            sheet.value(0, 1, "Price");
            sheet.value(2, 0, "Kurti");
            sheet.value(2, 1, 0.1 + 0.2);
            sheet.value(3, 0, "পাঞ্জাবি");
            sheet.value(3, 1, 1200);
        });

        Sheet sheet = reader.read(content);

        assertThat(sheet.type()).isEqualTo(Sheet.FileType.XLSX);
        assertThat(sheet.rows()).containsExactly(new Sheet.Row(1, List.of("Name", "Price")),
                new Sheet.Row(3, List.of("Kurti", "0.3")), new Sheet.Row(4, List.of("পাঞ্জাবি", "1200")));
    }

    @Test
    void unreadableFilesAreRefused() {
        assertCode(new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1},
                "import.unreadable");
        // Windows-1252 "Café", not UTF-8.
        assertCode(new byte[] {'C', 'a', 'f', (byte) 0xE9, ',', '1'}, "import.unreadable");
        assertCode(new byte[] {'P', 'K', 3, 4, 1, 2, 3}, "import.unreadable");
        assertCode(new byte[0], "import.empty");
        assertCode(" , \n\n,,\n".getBytes(StandardCharsets.UTF_8), "import.empty");
    }

    @Test
    void sheetsOverTheLimitsAreRefused() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i <= SheetReader.MAX_ROWS; i++) {
            rows.append("Item ").append(i).append(",1\n");
        }
        assertCode(rows.toString().getBytes(StandardCharsets.UTF_8), "import.too_big");
        assertCode(("x" + ",".repeat(SheetReader.MAX_COLUMNS) + "y\n").getBytes(StandardCharsets.UTF_8), "import.too_big");
    }

    private void assertCode(byte[] content, String code) {
        assertThatThrownBy(() -> reader.read(content)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code().code()).isEqualTo(code));
    }

    interface Filler {
        void fill(Worksheet sheet) throws IOException;
    }

    static byte[] xlsx(Filler filler) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Workbook workbook = new Workbook(out, "test", "1.0");
        filler.fill(workbook.newWorksheet("Sheet1"));
        workbook.finish();
        return out.toByteArray();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
