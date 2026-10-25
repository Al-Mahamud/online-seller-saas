package com.mojud.shared.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * New ids are UUID version 7 (RFC 9562): the first 48 bits are the creation time in milliseconds,
 * the rest random. They sort roughly by creation time, which keeps indexes compact, and they are
 * safe to show in URLs because they cannot be guessed or counted.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        return newId(Clock.systemUTC());
    }

    static UUID newId(Clock clock) {
        long millis = clock.millis();
        long randomHigh = RANDOM.nextLong();
        long randomLow = RANDOM.nextLong();
        long mostSignificant = (millis << 16) | 0x7000L | (randomHigh & 0x0FFFL);
        long leastSignificant = (randomLow & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}
