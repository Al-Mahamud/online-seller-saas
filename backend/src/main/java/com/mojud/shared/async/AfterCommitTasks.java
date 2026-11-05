package com.mojud.shared.async;

import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs short tasks, such as sending an SMS or email, on a background thread once the current
 * transaction has committed, or at once when there is none. The request does not wait for the
 * network, so its timing cannot reveal whether a message was sent, and no database connection is
 * held while sending. A rolled-back transaction sends nothing.
 *
 * <p>Tasks are not stored: one lost to a crash is simply not done. That suits one-time codes, which
 * the user asks for again; anything that must happen uses a db-scheduler job instead.
 *
 * <p>{@code mojud.async.background=false} (tests) runs tasks inline, still after commit.
 */
@Component
public class AfterCommitTasks implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AfterCommitTasks.class);

    private final ThreadPoolTaskExecutor pool;
    private final Executor executor;

    AfterCommitTasks(@Value("${mojud.async.background:true}") boolean background) {
        if (background) {
            pool = new ThreadPoolTaskExecutor();
            pool.setThreadNamePrefix("after-commit-");
            pool.setTaskDecorator(LogContext::carrying);
            pool.setCorePoolSize(2);
            pool.setMaxPoolSize(4);
            pool.setQueueCapacity(1000);
            // Under overload the request thread sends: slower, but nothing is dropped.
            pool.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
            pool.initialize();
            executor = pool;
        } else {
            pool = null;
            executor = Runnable::run;
        }
    }

    public void run(Runnable task) {
        Runnable guarded = () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("Background task failed", e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(guarded);
                }
            });
        } else {
            executor.execute(guarded);
        }
    }

    @Override
    public void destroy() {
        if (pool != null) {
            pool.shutdown();
        }
    }
}
