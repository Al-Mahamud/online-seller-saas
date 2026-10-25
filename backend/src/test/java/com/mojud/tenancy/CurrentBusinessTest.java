package com.mojud.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.shared.id.Ids;
import com.mojud.shared.tenant.TenantScope;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class CurrentBusinessTest {

    private final UUID business = Ids.newId();

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void isOnlyGivenWhileTheRequestWorksInThatBusiness() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentBusiness.class.getName(), new CurrentBusiness(business, Ids.newId(), BusinessRole.OWNER));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(TenantScope.call(business, CurrentBusiness::get)).isPresent();
        assertThat(TenantScope.call(Ids.newId(), CurrentBusiness::get)).isEmpty();
        assertThat(CurrentBusiness.get()).isEmpty();
    }

    @Test
    void isEmptyOutsideARequest() {
        assertThat(TenantScope.call(business, CurrentBusiness::get)).isEmpty();
    }
}
