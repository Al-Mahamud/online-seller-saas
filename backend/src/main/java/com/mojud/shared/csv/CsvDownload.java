package com.mojud.shared.csv;

import com.mojud.shared.time.DhakaTime;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;

/** Starts a CSV download: "orders-2026-10-28.csv", never cached (it holds the business's data). */
public final class CsvDownload {

    private CsvDownload() {
    }

    public static OutputStream start(HttpServletResponse response, String name, Clock clock) {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(name + "-" + DhakaTime.today(clock) + ".csv").build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        try {
            return response.getOutputStream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
