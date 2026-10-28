package com.mojud.identity;

import java.time.Instant;

/** Sign-up counts for platform metrics (system role). */
public interface UserMetrics {

    long users();

    long signUpsSince(Instant since);
}
