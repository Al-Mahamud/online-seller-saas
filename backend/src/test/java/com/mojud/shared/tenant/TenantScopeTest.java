package com.mojud.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mojud.shared.id.Ids;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantScopeTest {

    @Test
    void theScopeEndsWithItsWork() {
        UUID business = Ids.newId();

        assertThat(TenantScope.call(business, TenantScope::current)).contains(business);
        assertThat(TenantScope.current()).isEmpty();
    }

    @Test
    void anInnerScopeRestoresTheOuterOne() {
        UUID outer = Ids.newId();
        UUID inner = Ids.newId();

        TenantScope.run(outer, () -> {
            assertThat(TenantScope.call(inner, TenantScope::current)).contains(inner);
            assertThat(TenantScope.current()).contains(outer);
        });
    }

    @Test
    void theScopeEndsEvenWhenTheWorkFails() {
        assertThatThrownBy(() -> TenantScope.run(Ids.newId(), () -> {
            throw new IllegalStateException("boom");
        })).hasMessage("boom");

        assertThat(TenantScope.current()).isEmpty();
    }
}
