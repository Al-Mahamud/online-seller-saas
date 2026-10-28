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
    /** The variant's option values are no longer in the product's options. */
    VARIANT_NOT_IN_OPTIONS("variant.not_in_options", HttpStatus.CONFLICT),
    /** A product keeps at least one active variant; archive the product instead. */
    VARIANT_LAST_ACTIVE("variant.last_active", HttpStatus.CONFLICT),

    // Files.
    /** Recognised by content, not by name: anything that is not a readable JPEG, PNG or WebP. */
    UNSUPPORTED_FILE("file.unsupported", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    FILE_TOO_LARGE("file.too_large", HttpStatus.CONTENT_TOO_LARGE),
    /** {0} is the minimum side in pixels. */
    IMAGE_TOO_SMALL("file.image_too_small", HttpStatus.BAD_REQUEST),
    /** A product has at most 8 images. */
    PRODUCT_IMAGE_LIMIT("product.image_limit", HttpStatus.CONFLICT),

    /** An Idempotency-Key header was sent again with a different request. */
    IDEMPOTENCY_KEY_REUSED("idempotency.key_reused", HttpStatus.CONFLICT),

    // Stock.
    /** Not enough available stock; {@code shortages} lists each variant, requested and available. */
    STOCK_INSUFFICIENT("stock.insufficient", HttpStatus.CONFLICT),
    /** A change the stock cannot take, such as releasing more than is reserved. */
    STOCK_INVALID_CHANGE("stock.invalid_change", HttpStatus.CONFLICT),

    // Billing.
    /** Over the plan's limit; {@code metric} and {@code limit} say which. Nothing already there is hidden. */
    PLAN_LIMIT_REACHED("plan.limit_reached", HttpStatus.PAYMENT_REQUIRED),
    /** The trial ended or the plan went unpaid: read, export and pay only. */
    SUBSCRIPTION_READ_ONLY("subscription.read_only", HttpStatus.PAYMENT_REQUIRED),
    /** Suspended by an administrator: billing only. */
    SUBSCRIPTION_SUSPENDED("subscription.suspended", HttpStatus.PAYMENT_REQUIRED),
    /** The change needs a payment (a dearer plan, or a plan after the trial). */
    BILLING_PAYMENT_NEEDED("billing.payment_needed", HttpStatus.CONFLICT),

    // Customers.
    /** Another customer has this phone number; {@code customerId} names them. */
    CUSTOMER_PHONE_TAKEN("customer.phone_taken", HttpStatus.CONFLICT),

    // Orders.
    /** The order's status no longer allows this change (only drafts are edited, before 7b). */
    ORDER_NOT_EDITABLE("order.not_editable", HttpStatus.CONFLICT),
    /** The order's status does not allow this move; {@code status} is the status it is in. */
    ORDER_INVALID_TRANSITION("order.invalid_transition", HttpStatus.CONFLICT),
    /** Payments are recorded before shipping or after delivery, not while the courier has the parcel. */
    ORDER_NOT_PAYABLE("order.not_payable", HttpStatus.CONFLICT),
    /** Only delivered shipments with COD still with the courier are settled; {@code shipmentIds} lists the others. */
    COD_NOT_SETTLEABLE("cod.not_settleable", HttpStatus.CONFLICT),

    // Imports.
    /** Not an .xlsx or a UTF-8 .csv (an old .xls, a CSV in another encoding, a damaged file). */
    IMPORT_UNREADABLE("import.unreadable", HttpStatus.BAD_REQUEST),
    IMPORT_EMPTY("import.empty", HttpStatus.BAD_REQUEST),
    /** {0} rows, {1} columns: the most a sheet may have. */
    IMPORT_TOO_BIG("import.too_big", HttpStatus.CONTENT_TOO_LARGE),
    /** The import was committed; its mapping can no longer change. */
    IMPORT_NOT_EDITABLE("import.not_editable", HttpStatus.CONFLICT),
    /** Every row has errors; there is nothing to import. */
    IMPORT_NOTHING_VALID("import.nothing_valid", HttpStatus.CONFLICT),

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
