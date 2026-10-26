package com.mojud.shared.db;

import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tells which constraint refused a write. Unique indexes and foreign keys decide (no "already
 * taken?" query first, which two requests could pass together); the caller turns the violation
 * into the right error.
 */
public final class Constraints {

    private Constraints() {
    }

    public static boolean violated(DataIntegrityViolationException e, String constraintName) {
        return String.valueOf(NestedExceptionUtils.getMostSpecificCause(e).getMessage()).contains(constraintName);
    }

    /**
     * Runs {@code work} in its own transaction; if one of the named constraints refuses it, answers
     * with that constraint's error code instead of a server error. Other violations pass through.
     * Call it outside any transaction: the violation can surface at commit.
     */
    public static <T> T translating(TransactionTemplate transactions, Map<String, ErrorCode> errors, Supplier<T> work) {
        try {
            return transactions.execute(status -> work.get());
        } catch (DataIntegrityViolationException e) {
            for (Map.Entry<String, ErrorCode> error : errors.entrySet()) {
                if (violated(e, error.getKey())) {
                    throw new ApiException(error.getValue());
                }
            }
            throw e;
        }
    }
}
