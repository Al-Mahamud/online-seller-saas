/**
 * The audit log of sensitive actions (Phase 2c). Other modules write to it through
 * {@link com.mojud.shared.audit.AuditTrail}, inside the transaction of the change; this module owns
 * the table and lets owners read it.
 */
package com.mojud.audit;
