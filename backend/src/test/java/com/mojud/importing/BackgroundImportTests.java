package com.mojud.importing;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestBusinesses;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * The import on its own threads, as in production: the commit answers at once, and progress is
 * followed until the job is done. (Other tests run imports inline.)
 */
@IntegrationTest
@TestPropertySource(properties = "mojud.imports.background=true")
class BackgroundImportTests {

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Test
    void aCommittedImportRunsInTheBackgroundAndReportsProgress() throws InterruptedException {
        ApiClient owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        TestBusinesses.create(owner, "Background Shop");
        String id = owner.upload("/api/v1/imports", "bulk.csv", ImportCommitTests.twoThousandRows()).expectStatus(201)
                .json("$.id");

        ApiResponse queued = owner.post("/api/v1/imports/" + id + "/commit", null).expectStatus(202);
        assertThat(queued.<String>json("$.status")).isIn("QUEUED", "RUNNING");

        ApiResponse job = owner.get("/api/v1/imports/" + id);
        for (int i = 0; i < 600 && !"DONE".equals(job.json("$.status")); i++) {
            Thread.sleep(100);
            job = owner.get("/api/v1/imports/" + id);
        }
        assertThat(job.<String>json("$.status")).isEqualTo("DONE");
        assertThat(job.<Map<String, Integer>>json("$.progress")).containsEntry("created", 1963).containsEntry("skipped", 37);
        assertThat(job.<String>json("$.finishedAt")).isNotNull();
    }
}
