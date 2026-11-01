package com.mojud.shared.paging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

class PagedResultTest {

    private static final List<Integer> TWENTY_FIVE = IntStream.rangeClosed(1, 25).boxed().toList();

    @Test
    void aMissingPageAndSizeMeanTheFirstPageAtTheDefaultSize() {
        PageQuery query = new PageQuery(null, null);

        assertThat(query.number()).isEqualTo(1);
        assertThat(query.limit()).isEqualTo(PageQuery.DEFAULT_SIZE);
        assertThat(query.offset()).isZero();
    }

    @Test
    void anImpossiblePageOrSizeBecomesTheNearestSensibleOne() {
        assertThat(new PageQuery(0, 0).number()).isEqualTo(1);
        assertThat(new PageQuery(-3, -3).limit()).isEqualTo(1);
        assertThat(new PageQuery(1, 5000).limit()).isEqualTo(PageQuery.MAX_SIZE);
    }

    @Test
    void theLargestPageStillGivesAnOffsetADatabaseCanTake() {
        PageQuery query = new PageQuery(Integer.MAX_VALUE, PageQuery.MAX_SIZE);

        assertThat(query.offset()).isPositive().isLessThan(Integer.MAX_VALUE);
    }

    @Test
    void pagesCountFromOneAndSpringDataFromZero() {
        PageQuery query = new PageQuery(3, 20);

        assertThat(query.offset()).isEqualTo(40);
        assertThat(query.pageable().getPageNumber()).isEqualTo(2);
        assertThat(query.pageable().getPageSize()).isEqualTo(20);
    }

    @Test
    void theTotalPagesRoundUp() {
        assertThat(PagedResult.of(List.of(), new PageQuery(1, 20), 40).totalPages()).isEqualTo(2);
        assertThat(PagedResult.of(List.of(), new PageQuery(1, 20), 41).totalPages()).isEqualTo(3);
        assertThat(PagedResult.of(List.of(), new PageQuery(1, 20), 1).totalPages()).isEqualTo(1);
    }

    @Test
    void anEmptyListHasNoPages() {
        PagedResult<String> empty = PagedResult.empty(new PageQuery(4, 20));

        assertThat(empty.items()).isEmpty();
        assertThat(empty.page()).isEqualTo(4);
        assertThat(empty.totalItems()).isZero();
        assertThat(empty.totalPages()).isZero();
    }

    @Test
    void aPageIsCutFromAWholeList() {
        PagedResult<Integer> last = PagedResult.ofAll(TWENTY_FIVE, new PageQuery(3, 10));

        assertThat(last.items()).containsExactly(21, 22, 23, 24, 25);
        assertThat(last.totalItems()).isEqualTo(25);
        assertThat(last.totalPages()).isEqualTo(3);
    }

    @Test
    void aPagePastTheEndHasNoItemsAndKeepsTheTotals() {
        PagedResult<Integer> past = PagedResult.ofAll(TWENTY_FIVE, new PageQuery(9, 10));

        assertThat(past.items()).isEmpty();
        assertThat(past.page()).isEqualTo(9);
        assertThat(past.totalPages()).isEqualTo(3);
    }

    @Test
    void aSpringDataPageKeepsItsTotal() {
        PageQuery query = new PageQuery(2, 2);

        PagedResult<String> page = PagedResult.of(new PageImpl<>(List.of("c"), query.pageable(), 3), query);

        assertThat(page.items()).containsExactly("c");
        assertThat(page.totalItems()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(2);
    }

    @Test
    void mappingKeepsThePosition() {
        PagedResult<Integer> page = PagedResult.ofAll(TWENTY_FIVE, new PageQuery(2, 10));

        PagedResult<String> mapped = page.map(String::valueOf);

        assertThat(mapped.items()).hasSize(10).startsWith("11");
        assertThat(mapped.page()).isEqualTo(2);
        assertThat(mapped.size()).isEqualTo(10);
        assertThat(mapped.totalItems()).isEqualTo(25);
        assertThat(mapped.totalPages()).isEqualTo(3);
    }
}
