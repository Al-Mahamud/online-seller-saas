package com.mojud.importing.system;

import java.util.UUID;

/** Runs a queued import, or picks up a stalled one where it stopped. */
public interface ImportResumer {

    void run(UUID businessId, UUID jobId);
}
