package com.mojud.tenancy.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Businesses and their settings are keyed by the business id itself, so Hibernate's tenant filter
 * cannot cover them. Their repositories therefore offer only lookups by that id: nothing that could
 * return another business's row, such as findAll.
 */
class BusinessRepositoryShapeTest {

    @Test
    void onlyLookupsByIdAndSaves() {
        for (Class<?> repository : List.of(BusinessRepository.class, BusinessSettingsRepository.class)) {
            assertThat(Arrays.stream(repository.getMethods()).map(Method::getName))
                    .as(repository.getSimpleName())
                    .isSubsetOf("findById", "save", "saveAndFlush");
        }
    }
}
