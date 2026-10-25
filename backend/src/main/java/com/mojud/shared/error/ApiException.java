package com.mojud.shared.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A business error that reaches the client as a problem response with the given {@link ErrorCode}.
 * The message shown to the user comes from the code's message key, in the request's language;
 * {@code messageArgs} fill its placeholders. Details are added as extra properties (for example
 * the items that are out of stock) so the client can act on them.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Object[] messageArgs;
    private final Map<String, Object> details = new LinkedHashMap<>();

    public ApiException(ErrorCode code, Object... messageArgs) {
        super(code.code());
        this.code = code;
        this.messageArgs = messageArgs.clone();
    }

    public static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND);
    }

    /**
     * A validation error found by business code rather than by annotations, in the same
     * {@code errors} format the handler produces for annotated fields. {@code messageKey} is
     * looked up in the message files, like annotation messages.
     */
    public static ApiException invalidField(String field, String messageKey) {
        return new ApiException(ErrorCode.VALIDATION_FAILED)
                .withDetail("errors", List.of(new FieldProblem(field, messageKey)));
    }

    public ApiException withDetail(String name, Object value) {
        details.put(name, value);
        return this;
    }

    public ErrorCode code() {
        return code;
    }

    public Object[] messageArgs() {
        return messageArgs.clone();
    }

    public Map<String, Object> details() {
        return Collections.unmodifiableMap(details);
    }

    /** A field error whose message is still a key; the handler translates it. */
    public record FieldProblem(String field, String messageKey) {
    }
}
