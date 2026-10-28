package com.mojud.customers;

import java.io.OutputStream;
import java.util.Locale;

/** Every customer of the business as CSV, for the owner's data export. */
public interface CustomerData {

    void writeCustomersCsv(OutputStream out, Locale locale);
}
