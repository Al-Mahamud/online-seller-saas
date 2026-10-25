package com.mojud.tenancy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller (or one of its methods) as working inside the selected business, for members
 * with at least this role. Without a selected business the request fails with
 * {@code business.not_selected}; with a lower role, {@code access.denied}. A method annotation
 * overrides the class one.
 *
 * <p>Every endpoint that touches business data carries it; the cross-tenant suite (Phase 2c)
 * checks that.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequiresRole {

    BusinessRole value();
}
