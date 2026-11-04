package com.mojud.tenancy.web;

import com.mojud.tenancy.RequiresPermission;
import java.util.Arrays;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.tenancy.CurrentBusiness;
import com.mojud.tenancy.RequiresRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RequiresRole} before the controller method runs. A business-scoped write may also
 * name the business it means in {@code X-Business-Id}: the session's business moves by itself when
 * a member is removed (TenantFilter), and the write must not land in the next one.
 */
@Component
class RoleInterceptor implements HandlerInterceptor {

    static final String EXPECTED_BUSINESS = "X-Business-Id";

    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequiresRole required = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequiresRole.class);
        if (required == null) {
            required = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequiresRole.class);
        }
        if (required == null) {
            return true;
        }
        CurrentBusiness business = CurrentBusiness.get()
                .orElseThrow(() -> new ApiException(ErrorCode.BUSINESS_NOT_SELECTED));
        String expected = request.getHeader(EXPECTED_BUSINESS);
        if (expected != null && WRITES.contains(request.getMethod())
                && !expected.equalsIgnoreCase(business.businessId().toString())) {
            throw new ApiException(ErrorCode.BUSINESS_CHANGED);
        }
        if (!business.isAtLeast(required.value())) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        RequiresPermission areas = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequiresPermission.class);
        if (areas == null && AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequiresRole.class) == null) {
            // A method that sets its own role sets its own areas too: without any, it needs none.
            areas = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequiresPermission.class);
        }
        if (areas != null && areas.value().length > 0
                && Arrays.stream(areas.value()).noneMatch(business::can)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return true;
    }
}
