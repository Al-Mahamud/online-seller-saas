/**
 * Businesses, their settings, memberships with roles, staff invites and the current-business
 * context every request runs in (Phase 2).
 *
 * <p>Other modules use {@link com.mojud.tenancy.RequiresRole} on their controllers and
 * {@link com.mojud.tenancy.CurrentBusiness} to learn the business and role.
 */
package com.mojud.tenancy;
