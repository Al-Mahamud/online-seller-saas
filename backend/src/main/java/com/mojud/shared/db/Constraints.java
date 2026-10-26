package com.mojud.shared.db;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Tells which constraint refused a write. Unique indexes decide uniqueness (no "already taken?"
 * query first, which two requests could pass together); the caller turns the violation into the
 * right error.
 */
public final class Constraints {

    private Constraints() {
    }

    public static boolean violated(DataIntegrityViolationException e, String constraintName) {
        return String.valueOf(NestedExceptionUtils.getMostSpecificCause(e).getMessage()).contains(constraintName);
    }
}
