package com.mojud.tenancy;

import java.util.Optional;
import java.util.UUID;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The business the current request works in, and the signed-in user's role there. Set for each
 * request by the tenancy module from the session and a fresh membership check, so a removed member
 * or a changed role takes effect on the next request.
 */
public record CurrentBusiness(UUID businessId, UUID userId, BusinessRole role) {

    /** Empty outside a request, before sign-in, or when no business is selected. */
    public static Optional<CurrentBusiness> get() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        return request == null
                ? Optional.empty()
                : Optional.ofNullable((CurrentBusiness) request.getAttribute(CurrentBusiness.class.getName(), RequestAttributes.SCOPE_REQUEST));
    }

    /**
     * For code behind {@link RequiresRole}, where a business is always selected.
     *
     * @throws IllegalStateException otherwise
     */
    public static CurrentBusiness require() {
        return get().orElseThrow(() -> new IllegalStateException("No business selected; is @RequiresRole missing?"));
    }

    public boolean isAtLeast(BusinessRole required) {
        return role.atLeast(required);
    }
}
