package com.mojud.shared.paging;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Which page of a list is asked for, from the {@code page} and {@code size} query parameters. Pages
 * count from 1. A missing or impossible value becomes the nearest sensible one, so a hand-edited
 * address still shows a list instead of an error.
 */
public record PageQuery(@Nullable Integer page, @Nullable Integer size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    /** Keeps the offset within what a database query can take, even at the largest page size. */
    private static final int MAX_PAGE = 1_000_000;

    public PageQuery {
        page = page == null ? Integer.valueOf(1) : Integer.valueOf(within(page, MAX_PAGE));
        size = size == null ? Integer.valueOf(DEFAULT_SIZE) : Integer.valueOf(within(size, MAX_SIZE));
    }

    private static int within(int value, int most) {
        return Math.max(1, Math.min(value, most));
    }

    /** The number of the page: from 1. */
    public int number() {
        return page;
    }

    /** The number of rows on a page. */
    public int limit() {
        return size;
    }

    /** How many rows come before this page. */
    public long offset() {
        return (long) (number() - 1) * limit();
    }

    /** The same page for a Spring Data repository, which counts pages from 0. */
    public Pageable pageable() {
        return PageRequest.of(number() - 1, limit());
    }
}
