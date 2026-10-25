package com.mojud;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Fails the build when a module reaches into another module's internals or modules depend on
 * each other in a cycle (backend-plan.md §3).
 */
class ModularityTests {

    @Test
    void modulesRespectTheirBoundaries() {
        ApplicationModules.of(MojudApplication.class).verify();
    }
}
