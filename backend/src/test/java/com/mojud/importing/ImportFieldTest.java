package com.mojud.importing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ImportFieldTest {

    @Test
    void headersAreRecognisedInEnglishAndBangla() {
        assertThat(ImportField.recognise("Price (৳) *")).hasValue(ImportField.PRICE);
        assertThat(ImportField.recognise("  PRODUCT NAME: ")).hasValue(ImportField.NAME);
        assertThat(ImportField.recognise("পণ্যের নাম")).hasValue(ImportField.NAME);
        assertThat(ImportField.recognise("দাম")).hasValue(ImportField.PRICE);
        assertThat(ImportField.recognise("Qty")).hasValue(ImportField.STOCK);
        assertThat(ImportField.recognise("Colour")).hasValue(ImportField.OPTION);
        assertThat(ImportField.recognise("রং")).hasValue(ImportField.OPTION);
        assertThat(ImportField.recognise("Supplier phone")).isEmpty();
    }

    /** The app's own labels for these fields, as a seller copies them into a sheet. */
    @Test
    void theAppsOwnLabelsAreRecognised() {
        assertThat(ImportField.recognise("বিক্রয় দাম")).hasValue(ImportField.PRICE);
        assertThat(ImportField.recognise("বিক্রয় দাম (৳)")).hasValue(ImportField.PRICE);
        assertThat(ImportField.recognise("কেনা দাম (৳)")).hasValue(ImportField.COST_PRICE);
        assertThat(ImportField.recognise("কম স্টকের সীমা")).hasValue(ImportField.LOW_STOCK_THRESHOLD);
    }

    /** "য়" is one character (U+09DF) on some keyboards and two (য + ়) on others. */
    @Test
    void bothWaysOfWritingYaMatch() {
        String oneCharacter = "বিক্রয় দাম";
        String twoCharacters = "বিক্রয় দাম";
        assertThat(ImportField.recognise(oneCharacter)).hasValue(ImportField.PRICE);
        assertThat(ImportField.recognise(twoCharacters)).hasValue(ImportField.PRICE);
        assertThat(ImportField.recognise("ক্রয় মূল্য")).hasValue(ImportField.COST_PRICE);
    }

    @Test
    void optionNamesDropUnitsAndMarks() {
        assertThat(ImportField.optionName("Size (inch) *")).isEqualTo("Size");
        assertThat(ImportField.optionName("রং:")).isEqualTo("রং");
    }

    @Test
    void theHeaderRowIsFoundBelowATitle() {
        List<Sheet.Row> rows = List.of(new Sheet.Row(1, List.of("Eid stock list")),
                new Sheet.Row(3, List.of("Name", "Price", "Size", "Size", "Color", "Material", "Price")),
                new Sheet.Row(4, List.of("Kurti", "1200", "S")));

        Sheet.Row header = Mapping.findHeader(rows);
        Mapping mapping = Mapping.guess(header);

        assertThat(header.number()).isEqualTo(3);
        // The first Price only; the second Size is a repeat; at most three options.
        assertThat(mapping.columns()).containsExactly(new Mapping.Column(0, ImportField.NAME, null),
                new Mapping.Column(1, ImportField.PRICE, null), new Mapping.Column(2, ImportField.OPTION, "Size"),
                new Mapping.Column(4, ImportField.OPTION, "Color"), new Mapping.Column(5, ImportField.OPTION, "Material"));
    }

    @Test
    void columnLetters() {
        assertThat(List.of(0, 25, 26, 51, 52, 701, 702).stream().map(ImportService::letter))
                .containsExactly("A", "Z", "AA", "AZ", "BA", "ZZ", "AAA");
    }
}
