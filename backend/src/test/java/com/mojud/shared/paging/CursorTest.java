package com.mojud.shared.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mojud.shared.error.ApiException;
import com.mojud.shared.id.Ids;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CursorTest {

    @Test
    void encodesAndDecodesAnyKeyIncludingSeparatorsAndBangla() {
        Cursor cursor = new Cursor("2026-10-25T10:00:00Z|কুর্তি", Ids.newId());

        assertThat(Cursor.decode(cursor.encode())).isEqualTo(cursor);
        assertThat(cursor.encode()).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void noCursorMeansTheFirstPage() {
        assertThat(Cursor.decode(null)).isNull();
        assertThat(Cursor.decode(" ")).isNull();
    }

    @Test
    void aTamperedCursorIsAFieldError() {
        assertThatThrownBy(() -> Cursor.decode("not a cursor!"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details()).containsKey("errors"));
        assertThatThrownBy(() -> Cursor.decode("bm8tc2VwYXJhdG9y"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void theExtraRowOnlySignalsANextPage() {
        List<UUID> fetched = List.of(Ids.newId(), Ids.newId(), Ids.newId());

        CursorPage<UUID> page = CursorPage.of(fetched, 2, id -> new Cursor("k", id));

        assertThat(page.items()).containsExactly(fetched.get(0), fetched.get(1));
        assertThat(Cursor.decode(page.nextCursor()).id()).isEqualTo(fetched.get(1));
    }

    @Test
    void theLastPageHasNoCursor() {
        CursorPage<UUID> page = CursorPage.of(List.of(Ids.newId()), 2, id -> new Cursor("k", id));

        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void limitsAreClamped() {
        assertThat(CursorPage.limit(null)).isEqualTo(50);
        assertThat(CursorPage.limit(0)).isEqualTo(50);
        assertThat(CursorPage.limit(20)).isEqualTo(20);
        assertThat(CursorPage.limit(5000)).isEqualTo(100);
    }
}
