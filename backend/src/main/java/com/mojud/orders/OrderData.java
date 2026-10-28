package com.mojud.orders;

import java.io.OutputStream;
import java.util.Locale;

/** Every order of the business as CSV, for the owner's data export (no plan limits apply). */
public interface OrderData {

    void writeOrdersCsv(OutputStream out, Locale locale);
}
