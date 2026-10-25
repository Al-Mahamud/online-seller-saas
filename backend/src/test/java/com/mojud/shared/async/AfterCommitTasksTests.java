package com.mojud.shared.async;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class AfterCommitTasksTests {

    @Autowired
    AfterCommitTasks tasks;

    @Autowired
    TransactionTemplate transactions;

    @Test
    void runsOnlyAfterTheTransactionCommits() {
        List<String> done = new ArrayList<>();

        transactions.executeWithoutResult(status -> {
            tasks.run(() -> done.add("sent"));
            assertThat(done).isEmpty();
        });

        assertThat(done).containsExactly("sent");
    }

    @Test
    void aRolledBackTransactionRunsNothing() {
        List<String> done = new ArrayList<>();

        transactions.executeWithoutResult(status -> {
            tasks.run(() -> done.add("sent"));
            status.setRollbackOnly();
        });

        assertThat(done).isEmpty();
    }

    @Test
    void withoutATransactionItRunsAtOnce() {
        List<String> done = new ArrayList<>();

        tasks.run(() -> done.add("sent"));

        assertThat(done).containsExactly("sent");
    }

    @Test
    void aFailingTaskIsLoggedNotThrown() {
        tasks.run(() -> {
            throw new IllegalStateException("provider down");
        });
    }

    @Test
    void inBackgroundModeTheCallerDoesNotWait() throws Exception {
        AfterCommitTasks background = new AfterCommitTasks(true);
        try {
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);

            background.run(() -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                finished.countDown();
            });

            assertThat(finished.getCount()).isEqualTo(1);
            release.countDown();
            assertThat(finished.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            background.destroy();
        }
    }
}
