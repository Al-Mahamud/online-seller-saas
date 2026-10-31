package com.mojud.identity.account;

import com.mojud.shared.db.Constraints;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Saving a user can break the unique indexes on verified phones and emails (V5), when another
 * account proved the same one first, or on User IDs (V33), when two people choose one at the same
 * moment. The index that refused the write says which.
 */
public final class ContactConflicts {

    private ContactConflicts() {
    }

    /**
     * @return {@code account.phone_taken}, {@code account.email_taken} or
     *         {@code account.user_id_taken}, or {@code e} itself when another constraint failed
     */
    public static RuntimeException translate(DataIntegrityViolationException e) {
        if (Constraints.violated(e, "uq_users_user_id")) {
            return new ApiException(ErrorCode.USER_ID_TAKEN);
        }
        if (Constraints.violated(e, "uq_users_verified_phone")) {
            return new ApiException(ErrorCode.PHONE_TAKEN);
        }
        if (Constraints.violated(e, "uq_users_verified_email")) {
            return new ApiException(ErrorCode.EMAIL_TAKEN);
        }
        return e;
    }
}
