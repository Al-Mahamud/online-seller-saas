package com.mojud.shared.async;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class LogContextTests {

    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @AfterEach
    void cleanUp() {
        MDC.clear();
        pool.shutdownNow();
    }

    @Test
    void aBackgroundTaskLogsWithTheRequestIdOfWhoHandedItOver() throws Exception {
        MDC.put("requestId", "261110140020-e38a8e5");
        CompletableFuture<String> seen = new CompletableFuture<>();
        pool.execute(LogContext.carrying(() -> seen.complete(MDC.get("requestId"))));
        assertThat(seen.get(5, TimeUnit.SECONDS)).isEqualTo("261110140020-e38a8e5");

        // The pool's thread forgets it afterwards, so the next task can't log under the wrong request.
        MDC.clear();
        CompletableFuture<String> next = new CompletableFuture<>();
        pool.execute(() -> next.complete(String.valueOf(MDC.get("requestId"))));
        assertThat(next.get(5, TimeUnit.SECONDS)).isEqualTo("null");
    }

    @Test
    void runOnTheCallersOwnThreadItLeavesTheCallersContextAsItWas() {
        MDC.put("requestId", "aaaaaaaaaaaa");
        Runnable task = LogContext.carrying(() -> assertThat(MDC.get("requestId")).isEqualTo("aaaaaaaaaaaa"));
        MDC.put("requestId", "bbbbbbbbbbbb");

        task.run();

        assertThat(MDC.get("requestId")).isEqualTo("bbbbbbbbbbbb");
    }
}
