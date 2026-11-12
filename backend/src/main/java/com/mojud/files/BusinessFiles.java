package com.mojud.files;

import com.mojud.files.storage.FileStorage;
import com.mojud.tenancy.BusinessDeleted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A deleted business's files go too (implementation-plan.md 12b, 13b): its rows are already gone,
 * and every key starts with its id. A failure is logged for a person to finish; the deletion stands.
 */
@Component
class BusinessFiles {

    private static final Logger log = LoggerFactory.getLogger(BusinessFiles.class);

    private final FileStorage storage;

    BusinessFiles(FileStorage storage) {
        this.storage = storage;
    }

    @EventListener
    void on(BusinessDeleted event) {
        try {
            storage.deleteAll(event.businessId() + "/");
        } catch (RuntimeException e) {
            log.error("[FILES] deleting the files of deleted business {} failed", event.businessId(), e);
        }
    }
}
