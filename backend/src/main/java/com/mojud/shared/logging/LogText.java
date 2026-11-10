package com.mojud.shared.logging;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Bodies and addresses as the log shows them (docs/logging.md): secrets masked, kept on one line,
 * and cut at a length. Text that is not JSON, form fields or plain text, such as an image, is
 * named by its type and size instead of written out.
 */
public final class LogText {

    /** What a masked value is replaced with. */
    public static final String MASK = "***";

    /** JSON keys whose values are secrets: passwords, tokens, two-step secrets and recovery codes. */
    private static final Pattern SECRET_JSON = Pattern.compile(
            "\"([A-Za-z_]*(?:[Pp]assword|passwd|[Ss]ecret|[Tt]oken|otpauthUri|recoveryCodes?|sessionkey|verify_sign|verify_key))\""
                    + "\\s*:\\s*(?:\"(?:[^\"\\\\]|\\\\.)*\"?|\\[[^\\]]*\\]?|[^,}\\]\\s]+)");
    /** A one-time code: "code" holding digits. Other codes, such as an error's, are not secret. */
    private static final Pattern CODE_JSON = Pattern.compile("\"code\"\\s*:\\s*\"?\\d{4,8}\"?");
    /** Form fields and address parameters whose values are secrets. */
    private static final Pattern SECRET_NAME = Pattern.compile(
            "(?i).*(passw|secret|token|sessionkey|verify_sign|verify_key|otp).*|code");
    private static final Pattern SECRET_PARAMETER = Pattern.compile("([?&])([^=&#]+)=([^&#]*)");

    private LogText() {
    }

    /**
     * A body as the log shows it. {@code bytes} may be only the start of it ({@code total} is the
     * whole size, or -1 when not known).
     */
    public static String body(@Nullable String contentType, byte[] bytes, long total, int limit) {
        long size = total >= 0 ? total : bytes.length;
        if (size == 0) {
            return "";
        }
        if (!readable(contentType)) {
            return "<" + (contentType == null ? "body" : contentType.split(";")[0].trim()) + " " + size(size) + ">";
        }
        int shown = Math.min(bytes.length, limit);
        String text = new String(bytes, 0, shown, StandardCharsets.UTF_8);
        text = isForm(contentType) ? maskParameters("?" + text).substring(1) : maskJson(text);
        text = oneLine(text);
        return size > shown ? text + "…(+" + (size - shown) + " bytes)" : text;
    }

    /** Form fields, such as a gateway's callback, with secrets masked: {@code {"tran_id":"…",…}}. */
    public static String fields(Map<String, String[]> parameters, int limit) {
        StringJoiner joiner = new StringJoiner(",", "{", "}");
        parameters.forEach((name, values) -> joiner.add(quote(name) + ":"
                + quote(SECRET_NAME.matcher(name).matches() ? MASK : values.length == 0 ? "" : values[0])));
        String text = oneLine(joiner.toString());
        return text.length() > limit ? text.substring(0, limit) + "…" : text;
    }

    /** A path or URL with the values of secret parameters masked, such as a gateway's store password. */
    public static String maskParameters(String address) {
        Matcher matcher = SECRET_PARAMETER.matcher(address);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = SECRET_NAME.matcher(matcher.group(2)).matches() ? MASK : matcher.group(3);
            matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(1) + matcher.group(2) + "=" + value));
        }
        return matcher.appendTail(out).toString();
    }

    static String maskJson(String text) {
        String masked = SECRET_JSON.matcher(text).replaceAll(match -> Matcher.quoteReplacement(
                "\"" + match.group(1) + "\":\"" + MASK + "\""));
        return CODE_JSON.matcher(masked).replaceAll("\"code\":\"" + MASK + "\"");
    }

    /** Line breaks written as \n, so a body can't start a line of its own that looks like a log line. */
    static String oneLine(String text) {
        return text.replace("\r", "\\r").replace("\n", "\\n");
    }

    private static boolean readable(@Nullable String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.contains("json") || type.startsWith("text/plain") || isForm(type);
    }

    private static boolean isForm(@Nullable String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded");
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        return bytes < 1024 * 1024 ? String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
                : String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }
}
