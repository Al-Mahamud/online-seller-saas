package com.mojud.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.shared.web.RequestLogFilter;
import io.sentry.Breadcrumb;
import io.sentry.Hint;
import org.junit.jupiter.api.Test;

class SentryBreadcrumbsTest {

    private static String shown(String category, String message) {
        Breadcrumb breadcrumb = new Breadcrumb(message);
        breadcrumb.setCategory(category);
        return new SentryBreadcrumbs().execute(breadcrumb, new Hint()).getMessage();
    }

    @Test
    void requestLinesReachSentryWithoutBodiesOrSearchWords() {
        String request = RequestLogFilter.class.getName();
        assertThat(shown(request, "[CLIENT-REQ] POST /api/v1/customers\t{\"name\":\"Rahima\",\"phone\":\"01712345678\"}"))
                .isEqualTo("[CLIENT-REQ] POST /api/v1/customers");
        assertThat(shown(request, "[CLIENT-REQ] GET /api/v1/customers?q=Rahima&phone=01712"))
                .isEqualTo("[CLIENT-REQ] GET /api/v1/customers");
        assertThat(shown(OutboundLog.class.getName(), "[RES] GET https://gw/validator?val_id=1 200 8ms\t{\"status\":\"VALID\"}"))
                .isEqualTo("[RES] GET https://gw/validator 200 8ms");
        assertThat(shown("com.mojud.orders.BulkActions", "[BULK-ACTION] order 1 failed\tdetail"))
                .isEqualTo("[BULK-ACTION] order 1 failed\tdetail");
    }
}
