package com.mojud.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Machine-readable error codes, returned in the {@code code} property of every problem response.
 * Clients branch on the code, never on the message. Each code's message lives in
 * {@code messages*.properties} under {@code error.<code>}, in English and Bangla.
 *
 * <p>Later phases add their own codes here (for example {@code stock.insufficient}).
 */
public enum ErrorCode {

    VALIDATION_FAILED("validation.failed", HttpStatus.BAD_REQUEST),

    /** Also used for another business's resource, so ids cannot be probed (backend-plan.md §4). */
    NOT_FOUND("resource.not_found", HttpStatus.NOT_FOUND),

    /** Optimistic-lock conflict: someone else changed the resource first. */
    CONCURRENT_MODIFICATION("resource.modified", HttpStatus.CONFLICT),

    // Errors raised by the framework before a controller runs.
    MALFORMED_REQUEST("request.malformed", HttpStatus.BAD_REQUEST),
    ROUTE_NOT_FOUND("route.not_found", HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED("method.not_allowed", HttpStatus.METHOD_NOT_ALLOWED),
    UNSUPPORTED_MEDIA_TYPE("media_type.unsupported", HttpStatus.UNSUPPORTED_MEDIA_TYPE),

    /** Anything unexpected. The details go to the log, never to the client. */
    SERVER_ERROR("server.error", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final HttpStatus status;

    ErrorCode(String code, HttpStatus status) {
        this.code = code;
        this.status = status;
    }

    /** The value clients see, for example {@code resource.not_found}. */
    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public String messageKey() {
        return "error." + code;
    }
}
