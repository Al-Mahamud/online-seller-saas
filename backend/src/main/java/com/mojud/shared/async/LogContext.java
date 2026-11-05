package com.mojud.shared.async;

import java.util.Map;
import org.slf4j.MDC;

/**
 * Carries the log context (the request's ID, RequestIdFilter) from the thread that hands over a
 * task to the thread that runs it, so a background task's log lines, such as a failed SMS, can be
 * found with the request that started it. Used as a pool's task decorator.
 */
public final class LogContext {

    private LogContext() {
    }

    public static Runnable carrying(Runnable task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            // A pool that is full runs the task on the caller's own thread; leave its context as it was.
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (context == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(context);
            }
            try {
                task.run();
            } finally {
                if (previous == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previous);
                }
            }
        };
    }
}
