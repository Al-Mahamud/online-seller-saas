package com.mojud.shared.logging;

import com.mojud.shared.web.RequestLogFilter;
import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.SentryOptions;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Log lines at INFO and above go with a Sentry error as breadcrumbs. The request log's lines carry
 * bodies and search words (customers' names, phones, addresses), which Sentry must not get
 * ({@code send-default-pii: false}), so their breadcrumbs keep only the method, path, status and
 * time: {@code [CLIENT-RES] POST /api/v1/orders 201 38ms}.
 */
@Component
class SentryBreadcrumbs implements SentryOptions.BeforeBreadcrumbCallback {

    private static final Set<String> REQUEST_LOGS = Set.of(RequestLogFilter.class.getName(), OutboundLog.class.getName());
    private static final Pattern QUERY = Pattern.compile("\\?\\S*");

    @Override
    public Breadcrumb execute(Breadcrumb breadcrumb, Hint hint) {
        String message = breadcrumb.getMessage();
        if (message != null && REQUEST_LOGS.contains(breadcrumb.getCategory())) {
            int tab = message.indexOf('\t');
            breadcrumb.setMessage(QUERY.matcher(tab < 0 ? message : message.substring(0, tab)).replaceAll(""));
        }
        return breadcrumb;
    }
}
