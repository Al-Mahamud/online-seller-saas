package com.mojud.shared.paging;

import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * One page of a list. {@code nextCursor} is {@code null} on the last page.
 *
 * @param <T> the item type
 */
public record CursorPage<T>(List<T> items, @Nullable String nextCursor) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    /** The page size to use: the default when none is given, never more than the maximum. */
    public static int limit(Integer requested) {
        if (requested == null || requested < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }

    /**
     * Builds a page from a query that fetched up to {@code limit + 1} rows: the extra row only
     * shows that another page exists and is not returned.
     */
    public static <T> CursorPage<T> of(List<T> fetched, int limit, Function<T, Cursor> cursorOf) {
        if (fetched.size() <= limit) {
            return new CursorPage<>(List.copyOf(fetched), null);
        }
        List<T> items = List.copyOf(fetched.subList(0, limit));
        return new CursorPage<>(items, cursorOf.apply(items.get(limit - 1)).encode());
    }
}
