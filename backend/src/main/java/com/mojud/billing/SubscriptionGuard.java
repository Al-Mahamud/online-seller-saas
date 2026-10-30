package com.mojud.billing;

import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.tenancy.CurrentBusiness;
import com.mojud.tenancy.RequiresRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Set;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * What a business may do in its subscription status (backend-plan.md §8), for the business's own
 * endpoints (those with {@link RequiresRole}), after tenancy has checked the role. READ_ONLY: read,
 * export and pay; every other change is 402 {@code subscription.read_only}. SUSPENDED: billing only,
 * and it can't start a payment (Invoices). Billing, notifications, the data export and deleting the
 * business stay open: a business can always read why, and take or delete its data. So does reading
 * which business this is, which the web app needs in order to open at all. Endpoints that
 * are not the business's (sign-in, profile, switching or creating businesses, accepting invites)
 * are not affected.
 */
@Component
class SubscriptionGuard implements HandlerInterceptor {

    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");
    private static final String THE_BUSINESS = "/api/v1/business";
    /** Always open: billing (to pay) and the owner's own notifications (to read reminders and mark them read). */
    private static final List<String> OPEN = List.of("/api/v1/billing", "/api/v1/notifications", "/api/v1/business/deletion",
            "/api/v1/business/export");

    private final Subscriptions subscriptions;

    SubscriptionGuard(Subscriptions subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || !businessEndpoint(method)
                || OPEN.stream().anyMatch(request.getRequestURI()::startsWith) || CurrentBusiness.get().isEmpty()) {
            return true;
        }
        if (THE_BUSINESS.equals(request.getRequestURI()) && READS.contains(request.getMethod())) {
            return true;
        }
        String status = subscriptions.current().status();
        if ("SUSPENDED".equals(status)) {
            throw new ApiException(ErrorCode.SUBSCRIPTION_SUSPENDED);
        }
        if ("READ_ONLY".equals(status) && !READS.contains(request.getMethod())) {
            throw new ApiException(ErrorCode.SUBSCRIPTION_READ_ONLY);
        }
        return true;
    }

    private static boolean businessEndpoint(HandlerMethod method) {
        return AnnotatedElementUtils.hasAnnotation(method.getMethod(), RequiresRole.class)
                || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), RequiresRole.class);
    }
}
