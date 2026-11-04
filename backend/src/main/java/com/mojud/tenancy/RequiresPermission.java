package com.mojud.tenancy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On top of {@link RequiresRole}: the member must be allowed one of these areas in this business
 * ({@link Permission}); the owner always is. Otherwise {@code access.denied}. A method annotation
 * overrides the class one, and an empty one on a method needs no area (reading a list that
 * several areas share).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequiresPermission {

    Permission[] value();
}
