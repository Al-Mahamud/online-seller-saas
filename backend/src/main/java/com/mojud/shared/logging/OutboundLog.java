package com.mojud.shared.logging;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Writes two lines for every call the server makes to another service, such as the payment
 * gateway (docs/logging.md):
 *
 * <pre>
 * [REQ] POST https://sandbox.sslcommerz.com/gwprocess/v4/api.php	store_id=…&amp;store_passwd=***&amp;…
 * [RES] POST https://sandbox.sslcommerz.com/gwprocess/v4/api.php 200 412ms	{"status":"SUCCESS",…}
 * </pre>
 *
 * Secrets are masked and bodies cut as for the app's own calls. Add it to a RestClient with
 * {@code requestInterceptor(new OutboundLog(limit))}.
 */
public final class OutboundLog implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OutboundLog.class);

    private final int limit;

    public OutboundLog(int limit) {
        this.limit = limit;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String address = LogText.maskParameters(request.getURI().toString());
        MediaType sent = request.getHeaders().getContentType();
        String shown = LogText.body(sent == null ? null : sent.toString(), body, body.length, limit);
        log.info("[REQ] {} {}{}", request.getMethod(), address, shown.isEmpty() ? "" : "\t" + shown);
        long started = System.nanoTime();
        ClientHttpResponse response;
        try {
            response = execution.execute(request, body);
        } catch (IOException e) {
            log.warn("[RES] {} {} failed after {}ms: {}", request.getMethod(), address,
                    (System.nanoTime() - started) / 1_000_000, e.toString());
            throw e;
        }
        byte[] answer = response.getBody().readAllBytes();
        MediaType received = response.getHeaders().getContentType();
        String answered = LogText.body(received == null ? null : received.toString(), answer, answer.length, limit);
        log.info("[RES] {} {} {} {}ms{}", request.getMethod(), address, response.getStatusCode().value(),
                (System.nanoTime() - started) / 1_000_000, answered.isEmpty() ? "" : "\t" + answered);
        return new Read(response, answer);
    }

    /** The answer with its body already read, so the caller can read it again. */
    private record Read(ClientHttpResponse response, byte[] body) implements ClientHttpResponse {

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return response.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return response.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return response.getHeaders();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void close() {
            response.close();
        }
    }
}
