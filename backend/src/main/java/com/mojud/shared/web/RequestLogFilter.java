package com.mojud.shared.web;

import com.mojud.shared.logging.LogText;
import com.mojud.shared.logging.LoggingProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.SequenceInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Writes two lines for every API call (docs/logging.md):
 *
 * <pre>
 * [CLIENT-REQ] POST /api/v1/orders	{"customerName":"…",…}
 * [CLIENT-RES] POST /api/v1/orders 201 38ms	{"id":"…",…}
 * </pre>
 *
 * The request line comes before anything else is done, the answer line after it is sent. Secrets
 * in bodies and addresses are masked, each body is cut at {@code mojud.logging.body-limit}, and a
 * file or image is named by its type and size. Runs inside {@link RequestIdFilter}, so both lines
 * carry the request's ID; the answer line also carries the signed-in user (MDC {@code userId}, set once Spring Security has
 * loaded the session).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@EnableConfigurationProperties(LoggingProperties.class)
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    private final LoggingProperties settings;

    RequestLogFilter(LoggingProperties settings) {
        this.settings = settings;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !log.isInfoEnabled() || settings.quietPaths().stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        int limit = settings.bodyLimit();
        String method = request.getMethod();
        String path = request.getRequestURI();
        String query = request.getQueryString();

        HttpServletRequest read = request;
        String requestBody;
        if (isForm(request.getContentType())) {
            requestBody = LogText.fields(request.getParameterMap(), limit);
        } else if (request.getContentLengthLong() != 0 && readable(request.getContentType())) {
            PeekedRequest peeked = new PeekedRequest(request, limit);
            read = peeked;
            requestBody = LogText.body(request.getContentType(), peeked.head, request.getContentLengthLong(), limit);
        } else {
            requestBody = LogText.body(request.getContentType(), new byte[0], Math.max(request.getContentLengthLong(), 0), limit);
        }
        log.info("[CLIENT-REQ] {} {}{}", method, LogText.maskParameters(query == null ? path : path + "?" + query),
                tab(requestBody));

        CopiedResponse copied = new CopiedResponse(response, limit);
        boolean failed = true;
        try {
            chain.doFilter(read, copied);
            failed = false;
        } finally {
            copied.flushWriter();
            long ms = (System.nanoTime() - started) / 1_000_000;
            int status = failed ? 500 : copied.getStatus();
            log.info("[CLIENT-RES] {} {} {} {}ms{}", method, path, status, ms,
                    tab(LogText.body(copied.getContentType(), copied.head(), copied.written(), limit)));
        }
    }

    private static String tab(String body) {
        return body.isEmpty() ? "" : "\t" + body;
    }

    private static boolean isForm(@Nullable String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded");
    }

    private static boolean readable(@Nullable String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.contains("json") || type.startsWith("text/");
    }

    /** A request whose first bytes were read for the log; the handler still reads the whole body. */
    private static final class PeekedRequest extends HttpServletRequestWrapper {

        private final byte[] head;
        private final ServletInputStream stream;
        private @Nullable BufferedReader reader;

        PeekedRequest(HttpServletRequest request, int limit) throws IOException {
            super(request);
            ServletInputStream original = request.getInputStream();
            this.head = original.readNBytes(limit);
            InputStream all = new SequenceInputStream(new ByteArrayInputStream(head), original);
            this.stream = new ServletInputStream() {
                private boolean finished;

                @Override
                public int read() throws IOException {
                    int next = all.read();
                    finished = next < 0;
                    return next;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int count = all.read(buffer, offset, length);
                    finished = count < 0;
                    return count;
                }

                @Override
                public boolean isFinished() {
                    return finished;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("The API reads request bodies blocking");
                }
            };
        }

        @Override
        public ServletInputStream getInputStream() {
            return stream;
        }

        @Override
        public BufferedReader getReader() {
            if (reader == null) {
                String encoding = getCharacterEncoding();
                Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
                reader = new BufferedReader(new InputStreamReader(stream, charset));
            }
            return reader;
        }
    }

    /** A response that keeps a copy of its first bytes for the log while sending everything as usual. */
    private static final class CopiedResponse extends HttpServletResponseWrapper {

        private final int limit;
        private final ByteArrayOutputStream head = new ByteArrayOutputStream();
        private long written;
        private @Nullable ServletOutputStream stream;
        private @Nullable PrintWriter writer;

        CopiedResponse(HttpServletResponse response, int limit) {
            super(response);
            this.limit = limit;
        }

        byte[] head() {
            return head.toByteArray();
        }

        long written() {
            return written;
        }

        void flushWriter() {
            if (writer != null) {
                writer.flush();
            }
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (stream == null) {
                ServletOutputStream original = super.getOutputStream();
                stream = new ServletOutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        original.write(b);
                        copy(new byte[] {(byte) b}, 0, 1);
                    }

                    @Override
                    public void write(byte[] bytes, int offset, int length) throws IOException {
                        original.write(bytes, offset, length);
                        copy(bytes, offset, length);
                    }

                    @Override
                    public void flush() throws IOException {
                        original.flush();
                    }

                    @Override
                    public boolean isReady() {
                        return original.isReady();
                    }

                    @Override
                    public void setWriteListener(WriteListener listener) {
                        original.setWriteListener(listener);
                    }
                };
            }
            return stream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                String encoding = getCharacterEncoding();
                Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
                writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), charset));
            }
            return writer;
        }

        @Override
        public void flushBuffer() throws IOException {
            flushWriter();
            super.flushBuffer();
        }

        private void copy(byte[] bytes, int offset, int length) {
            written += length;
            int room = limit - head.size();
            if (room > 0) {
                head.write(bytes, offset, Math.min(room, length));
            }
        }
    }
}
