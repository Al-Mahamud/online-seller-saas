package com.mojud.shared.paging;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * One page of a list, with what a pager needs to draw itself: the same answer from every list
 * endpoint. A page past the end has no items and still the right totals, so a client can go back.
 *
 * @param page the number of this page, from 1
 * @param size the most rows a page can have
 * @param totalItems the rows in the whole list, under the same filters
 * @param totalPages the pages in the whole list; 0 for an empty list
 * @param <T> the item type
 */
public record PagedResult<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    /** A page read with a limit and an offset, and the count of the whole list. */
    public static <T> PagedResult<T> of(List<T> items, PageQuery query, long totalItems) {
        int totalPages = (int) ((totalItems + query.limit() - 1) / query.limit());
        return new PagedResult<>(List.copyOf(items), query.number(), query.limit(), totalItems, totalPages);
    }

    /** A page from a Spring Data repository. */
    public static <T> PagedResult<T> of(Page<T> page, PageQuery query) {
        return of(page.getContent(), query, page.getTotalElements());
    }

    /** A page cut from a list that has to be worked out whole, such as one sorted by a computed value. */
    public static <T> PagedResult<T> ofAll(List<T> all, PageQuery query) {
        int from = (int) Math.min(query.offset(), all.size());
        int to = Math.min(from + query.limit(), all.size());
        return of(all.subList(from, to), query, all.size());
    }

    public static <T> PagedResult<T> empty(PageQuery query) {
        return of(List.of(), query, 0);
    }

    /** The same page with each item turned into something else. */
    public <R> PagedResult<R> map(Function<? super T, ? extends R> mapper) {
        return mapItems(rows -> rows.stream().<R>map(mapper).toList());
    }

    /** The same page with all its items turned into something else at once, for what's read in one query. */
    public <R> PagedResult<R> mapItems(Function<List<T>, List<R>> mapper) {
        return new PagedResult<>(mapper.apply(items), page, size, totalItems, totalPages);
    }
}
