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

    RATE_LIMITED("rate_limited", HttpStatus.TOO_MANY_REQUESTS),

    // Authentication and accounts.
    UNAUTHENTICATED("auth.required", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("access.denied", HttpStatus.FORBIDDEN),
    CSRF_TOKEN_INVALID("csrf.invalid", HttpStatus.FORBIDDEN),
    /** Wrong password, wrong code or unknown account; deliberately not distinguished. */
    INVALID_CREDENTIALS("auth.invalid_credentials", HttpStatus.UNAUTHORIZED),
    ACCOUNT_DISABLED("auth.account_disabled", HttpStatus.FORBIDDEN),
    /** Wrong, expired, already used or too many attempts; deliberately not distinguished. */
    OTP_INVALID("otp.invalid", HttpStatus.BAD_REQUEST),
    /** No sender is configured for this channel (no SMS gateway yet, backend-plan.md §13). */
    OTP_CHANNEL_UNAVAILABLE("otp.channel_unavailable", HttpStatus.BAD_REQUEST),
    PHONE_TAKEN("account.phone_taken", HttpStatus.CONFLICT),
    EMAIL_TAKEN("account.email_taken", HttpStatus.CONFLICT),
    /** A signed-in user re-entered their password wrongly. */
    WRONG_PASSWORD("account.wrong_password", HttpStatus.BAD_REQUEST),

    // Businesses.
    /** The request needs a selected business and the session has none; the client shows the switcher. */
    BUSINESS_NOT_SELECTED("business.not_selected", HttpStatus.CONFLICT),
    /** {0} is the limit. */
    BUSINESS_LIMIT_REACHED("business.limit_reached", HttpStatus.CONFLICT),

    // Staff and invites.
    STAFF_ALREADY_MEMBER("staff.already_member", HttpStatus.CONFLICT),
    /** The owner is changed only by transferring ownership, never by role changes or removal. */
    STAFF_OWNER_PROTECTED("staff.owner_protected", HttpStatus.CONFLICT),
    /** The member a business is handed to already owns the most businesses allowed; {0} is the limit. */
    STAFF_OWNER_LIMIT("staff.owner_limit", HttpStatus.CONFLICT),
    /** Members cannot change their own role or remove themselves. */
    STAFF_SELF_CHANGE("staff.self_change", HttpStatus.CONFLICT),
    /** Unknown, expired, used or revoked; deliberately not distinguished. */
    INVITE_INVALID("invite.invalid", HttpStatus.NOT_FOUND),
    /** {0} is the invited phone or email, partly hidden. */
    INVITE_WRONG_ACCOUNT("invite.wrong_account", HttpStatus.FORBIDDEN),
    /** No sender for this channel (no SMS gateway yet, or no email provider configured). */
    INVITE_CHANNEL_UNAVAILABLE("invite.channel_unavailable", HttpStatus.BAD_REQUEST),

    // Catalog.
    CATEGORY_NAME_TAKEN("category.name_taken", HttpStatus.CONFLICT),
    /** Categories are two levels deep: a category and its subcategories. */
    CATEGORY_TOO_DEEP("category.too_deep", HttpStatus.CONFLICT),
    /** Only an empty category (no subcategories, and from 3b no products) can be deleted. */
    CATEGORY_NOT_EMPTY("category.not_empty", HttpStatus.CONFLICT),
    TAG_NAME_TAKEN("tag.name_taken", HttpStatus.CONFLICT),
    /** SKUs are unique in a business, ignoring case. */
    SKU_TAKEN("product.sku_taken", HttpStatus.CONFLICT),

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
