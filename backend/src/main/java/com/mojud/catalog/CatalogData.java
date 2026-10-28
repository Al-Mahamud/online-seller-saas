package com.mojud.catalog;

import java.io.OutputStream;
import java.util.Locale;

/** Every product (with stock) of the business as CSV, for the owner's data export. */
public interface CatalogData {

    void writeProductsCsv(OutputStream out, Locale locale);
}
