package com.mojud.audit;

import java.time.Instant;

/** How many businesses are in use, for platform metrics: a member changed something there (system role). */
public interface ActivityMetrics {

    long activeBusinessesSince(Instant since);
}
