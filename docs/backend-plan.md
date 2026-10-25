# Backend Plan: Online Seller SaaS (MVP + Release 2 outline)

Draft for review, Oct 25, 2026. Source: `docs/product-plan.md` (the product plan, with the answers you ticked in its Open decisions section).

How to review: each section opens with its decision. Section 13 records your answers; section 14 lists the departures from the product plan. Anything marked **(changed)** departs from the product plan; the reason is given next to it.

---

## 1. Decisions carried over from the product plan

| Question | Your answer | What the backend does with it |
| --- | --- | --- |
| Backend language | Spring Boot | Java + Spring Boot 4, the same setup as the homemade-marketplace project |
| Team | Part-time | Small phases, one commit each, so each can be reviewed in one sitting |
| Market | Bangladesh only | BDT only, money stored as `bigint` poisha, `Asia/Dhaka` for "today" and "this month", BD phone numbers only |
| Pricing | Can edit | Plans and limits are rows in a table; admin can change them without a release |
| AI on Starter | Only on Pro | Starter's `ai_replies` limit is 0; no add-on packs for Starter |
| Stock timing | Seller chooses (13.1) | Business setting `stock_commit_point`: `SHIP` (reserve at confirm, deduct at ship; default) or `CONFIRM` (deduct at confirm) |
| AI order confirm | Both | Business setting `ai_auto_confirm` (default off); release 2 |
| WhatsApp fees | Seller pays | Seller's own WABA billing; we only show their usage; release 2 |
| App language | Both | Every user has `locale` (`bn` or `en`); emails, SMS, notifications and error messages exist in both |
| Free plan | 14-day Pro trial | No Free plan. New business starts `TRIALING` on Pro limits for 14 days (see 13.3) |
| Couriers | Manual entry in MVP | Staff type courier name and tracking number; API booking in release 2 |
| Trial end | Read-only | Unpaid trial → READ_ONLY, data kept 90 days |
| SMS gateway, SSLCommerz credentials | Later | Built behind interfaces: log/email OTP and SSLCommerz sandbox until you add them |
| Product name | Claude picks | Working name **Mojud** (মজুদ, "stock"), package `com.mojud`, repo `online-seller-saas` |

---

## 2. Stack

One Spring Boot application with PostgreSQL as the only stateful service. **(changed)** No Redis in the MVP: jobs, rate limits and counters live in PostgreSQL, which removes one service to run, back up and monitor.

| Concern | Choice | Notes |
| --- | --- | --- |
| Language / runtime | Java 17, Spring Boot 4.1.x | Your choice. No virtual threads, so in release 2 calls to Meta and the LLM run on a bounded worker pool. Moving to Java 21 later is a one-line change. |
| Build | Maven wrapper | Same as the marketplace project |
| Module boundaries | Spring Modulith | `verify()` test fails the build if a module touches another module's internals |
| Web | Spring MVC, Bean Validation, Problem Details (RFC 9457) errors | |
| Persistence | Spring Data JPA (Hibernate 7) + `JdbcClient` for reports and ledger | Hibernate `@TenantId` adds the tenant filter automatically (see 4) |
| Migrations | Flyway, SQL files in the repo | |
| Database | PostgreSQL 17 or 18, `pg_trgm` for search | Row-level security as the second tenant guard |
| Auth | Spring Security, server sessions in Spring Session JDBC **(changed)** | The plan said access + refresh tokens. The app is web-only and same-site, so an httpOnly session cookie is simpler, can be revoked at once, and picks up role changes at once. Same as the marketplace project. |
| Password hashing | Argon2id (Bouncy Castle) | |
| Background jobs | db-scheduler (PostgreSQL-backed) **(changed from BullMQ)** | Recurring and one-off jobs, safe with several app instances |
| Cross-module events | Spring Modulith events with the JDBC event publication registry | An event that fails its listener is retried after restart |
| Rate limits | Bucket4j, in memory | One app instance at launch; switch to the Bucket4j PostgreSQL backend if we run two |
| Files | S3 API (Cloudflare R2) via AWS SDK v2; local disk in dev | Uploads go browser → R2 with presigned URLs |
| Images | Thumbnailator; EXIF stripped on re-encode | |
| Excel / CSV | Apache POI streaming reader for `.xlsx`, FastCSV for `.csv` | |
| API docs | springdoc-openapi | The frontend generates its typed client from this |
| Observability | Sentry Java SDK, Spring Boot structured JSON logs, Actuator health | |
| Tests | JUnit 5, AssertJ, zonky embedded PostgreSQL | No Docker needed, which suits this machine |
| Container image | Jib (builds images without a Docker daemon), built in CI | |
| Real-time (release 2) | Server-Sent Events **(changed from Socket.IO)** | Inbox only needs server → browser pushes; SSE needs no extra library. Revisit if two-way is needed. |

**Process layout.** One jar, one config switch `app.roles=api,worker`. At launch, one process runs both. When the AI load grows, run a second copy with `app.roles=worker` and no HTTP traffic. This keeps the product plan's "API process and worker process" without the cost of running two on day one.

---

## 3. Modules

Each module is a top-level package under `com.mojud`. A module exposes a small public API (services, DTOs, events) and keeps its entities and repositories internal. Other modules never read its tables.

| Module | Owns (tables) | Public API used by others | Release |
| --- | --- | --- | --- |
| `shared` | none | `Money`, `PhoneNumber`, `BusinessId`, paging, error types, clock, crypto | MVP |
| `identity` | users, otp_challenges, user_sessions (Spring Session) | `CurrentUser`, `UserDirectory` | MVP |
| `tenancy` | businesses, memberships, invites, business_settings | `TenantContext`, `Memberships`, `BusinessSettings` | MVP |
| `catalog` | categories, tags, products, product_options, product_variants, product_images | `CatalogQueries` (search, get variant + price) | MVP |
| `inventory` | stock_movements (ledger), stock_levels | `StockService` (reserve, release, ship, restock, write off), `StockQueries` | MVP |
| `importing` | import_jobs, import_rows | none (calls catalog + inventory APIs) | MVP |
| `customers` | customers | `CustomerService` (find-or-create by phone) | MVP |
| `delivery` | divisions, districts, areas (reference data), delivery_zones | `DeliveryCharges.quote(area)` | MVP |
| `orders` | orders, order_items, order_events, shipments, payments, returns, return_items | `OrderQueries`, order events | MVP |
| `reporting` | none (composes other modules' report queries) | dashboard endpoints only | MVP |
| `billing` | plans, subscriptions, usage_counters, invoices, payment_attempts, billing_ledger | `Entitlements` (can this business do X?) | MVP |
| `notifications` | notifications, notification_preferences | `Notifier` (in-app, email, SMS) | MVP |
| `files` | uploads | `FileStorage`, presign | MVP |
| `audit` | audit_logs | listens to events | MVP |
| `admin` | none (combines module admin APIs) | platform-admin endpoints | MVP |
| `channels` | channels, webhook_events | `ChannelSender` adapter (Messenger, WhatsApp) | R2 |
| `inbox` | conversations, messages | | R2 |
| `ai` | ai_settings, ai_runs, ai_eval_cases | | R2 |
| `courier` | courier_accounts, courier_bookings | courier adapter (Steadfast, Pathao, RedX) | R2 |

Two conventions from the marketplace project carry over: admin endpoints live in the module that owns the data, and the `admin` module only combines module APIs. The same holds for `reporting`: each module answers its own report queries, `reporting` assembles the dashboard.

---

## 4. Tenant isolation (the most important section)

Two independent layers, each enough on its own, plus a test suite that attacks both.

**Layer 1: application.**
- The business comes from the session (`activeBusinessId` set at login or business switch, re-checked against `memberships`). It is never read from a path, query string or body.
- Tenant entities carry a `business_id` column annotated with Hibernate `@TenantId`. Hibernate adds `business_id = ?` to every JPA query and fills it on insert. Native and `JdbcClient` queries must include it by hand; a review checklist item and the cross-tenant tests catch misses.
- Every URL that takes an id looks the row up inside the tenant. A foreign id returns **404**, never 403, so ids from other tenants cannot be probed.

**Layer 2: PostgreSQL row-level security.**
- Every tenant table has `business_id` (child tables too, such as `order_items` and `stock_movements`, so each policy is one simple comparison) and:
  ```sql
  ALTER TABLE orders ENABLE ROW LEVEL SECURITY;
  ALTER TABLE orders FORCE ROW LEVEL SECURITY;
  CREATE POLICY tenant_isolation ON orders
    USING (business_id = current_setting('app.business_id', true)::uuid)
    WITH CHECK (business_id = current_setting('app.business_id', true)::uuid);
  ```
- A wrapper around the connection pool runs `select set_config('app.business_id', ?, false)` every time a connection is handed out (empty string when no tenant). An unset value matches no rows, so a missing context fails closed.
- Child rows reference parents with composite keys, for example `FOREIGN KEY (business_id, order_id) REFERENCES orders (business_id, id)`, so a row can never point at another tenant's parent.

**Three database roles.**

| Role | Used by | RLS |
| --- | --- | --- |
| `seller_owner` | Flyway migrations only | Owns tables |
| `seller_app` | All request handling | Enforced |
| `seller_system` | Scheduled jobs that scan all tenants, payment gateway callbacks, platform admin | `BYPASSRLS` |

The `seller_system` connection is a separate bean that only classes in `..system..` packages may inject; an ArchUnit test enforces that. Jobs that act on one tenant (for example "release this expired reservation") switch to `seller_app` with that tenant set.

**Cross-tenant test suite (runs in CI on every commit).**
1. Seed businesses A and B with one of everything.
2. List every endpoint from Spring's handler mappings.
3. For every endpoint with an id, call it as a B user with A's id: expect 404 (or 403 for admin routes). For list endpoints, expect none of A's rows.
4. A new endpoint without a test case fails the suite, so coverage cannot silently drop.
5. A raw-SQL test as `seller_app` with no tenant set must read zero rows from every tenant table.

---

## 5. Data model

All ids are UUID v7 (time-ordered, safe to expose). Every table has `created_at`; mutable ones have `updated_at` and a `version` column for optimistic locking. Money is `bigint` poisha. Timestamps are `timestamptz`, stored in UTC.

Additions and changes to the product plan's table list are marked **new** or **(changed)**.

### 5.1 Identity and tenancy

| Table | Key columns | Notes |
| --- | --- | --- |
| users | id, phone (E.164, unique), email (unique, nullable), password_hash, name, locale, phone_verified_at, is_platform_admin, status | Phone is the main login. Email optional, used for receipts and as a second OTP route. |
| otp_challenges **new** | id, phone_or_email, purpose (signup, login, reset, change_phone), code_hash, expires_at, attempts, consumed_at | 6 digits, 5 min, 5 tries |
| businesses | id, name, slug, logo_file_id, phone, address, timezone (`Asia/Dhaka`), status (active, suspended, deleted) | Currency column dropped **(changed)**: BDT only |
| business_settings **new** | business_id, stock_commit_point, low_stock_default, order_number_prefix, next_order_number, invoice_footer, ai_auto_confirm (R2) | One row per business |
| memberships | business_id, user_id, role (OWNER, MANAGER, STAFF), status | Exactly one OWNER per business (partial unique index) |
| invites **new** | id, business_id, phone_or_email, role, token_hash, expires_at, accepted_at, invited_by | Token sent by SMS or email; 7-day expiry |

### 5.2 Catalog and inventory

| Table | Key columns | Notes |
| --- | --- | --- |
| categories | business_id, id, name, parent_id, position | Two levels deep at most |
| tags, product_tags | business_id, name / product_id, tag_id | |
| products | business_id, id, category_id, name, description, status (active, archived) | Never hard-deleted once sold |
| product_options **new** | business_id, product_id, name ("Size"), values (text[]), position | Defines the variant grid |
| product_variants | business_id, id, product_id, sku, attributes (jsonb, e.g. `{"Size":"L","Color":"Black"}`), price, cost_price, low_stock_threshold, status | Unique `(business_id, sku)` and `(product_id, attributes)`. **Every product has at least one variant**: a product with no sizes gets one default variant, so stock and orders always point at a variant. |
| product_images **new** | business_id, id, product_id, variant_id (nullable), file_id, position | Replaces the `images` array; up to 8 per product |
| stock_levels **new** | business_id, variant_id, on_hand, reserved | Cache of the ledger, kept in a separate table **(changed)** so locking stock does not lock catalog edits. `available = on_hand − reserved`. Check constraints: `on_hand >= 0`, `reserved >= 0`. |
| stock_movements | business_id, id, variant_id, type, on_hand_delta, reserved_delta, reason, order_id, return_id, import_job_id, user_id, note, created_at | Append-only (no UPDATE or DELETE granted to `seller_app`). Two delta columns **(changed)** so reservations are in the ledger too. |

Movement types and their effect:

| Type | on_hand | reserved | When |
| --- | --- | --- | --- |
| OPENING | +q | | Product created with opening stock, or import |
| RECEIVE | +q | | Staff records new stock |
| ADJUST | ±q | | Count correction (reason required) |
| DAMAGE | −q | | Write-off from shelf |
| RESERVE | | +q | Order confirmed (or AI draft, R2) |
| RELEASE | | −q | Order cancelled, item removed, draft expired |
| SHIP | −q | −q | Order handed to courier |
| SALE | −q | | Order confirmed, when the business deducts at confirm |
| UNSALE | +q | | Such an order cancelled before shipping |
| RETURN | +q | | Returned item restocked |
| RETURN_DAMAGED | 0 | | Returned item written off (logged for the report, no stock change) |

A nightly job recomputes `SUM(deltas)` per variant and compares with `stock_levels`; any mismatch goes to Sentry and the admin.

### 5.3 Customers, delivery, orders

| Table | Key columns | Notes |
| --- | --- | --- |
| customers | business_id, id, name, phone (E.164), alt_phone, address, district_id, area_id, note, channel_ids (jsonb, R2), order_count, last_order_at | Unique `(business_id, phone)` |
| divisions, districts, areas **new** | id, name_en, name_bn, parent | Shared reference data, no RLS. Seeded from a public BD geocode dataset (licence to be checked). |
| delivery_zones **new** | business_id, id, name, charge, district_ids (uuid[]), is_default | Example: "Inside Dhaka ৳70", "Outside Dhaka ৳130". The order form and the AI's `get_policy` both use it. |
| orders | business_id, id, number, customer_id, status, source (manual, ai_draft), subtotal, discount, delivery_charge, total, advance_paid, cod_amount, payment_status, delivery snapshot (name, phone, address, district, area), note, confirmed_at, shipped_at, delivered_at, cancelled_at, reservation_expires_at (R2), version | Delivery address is copied onto the order, so editing the customer later does not change old orders |
| order_items | business_id, id, order_id, variant_id, product_name, variant_label, sku, qty, unit_price, unit_cost | Name, price and cost copied at order time |
| order_events | business_id, order_id, from_status, to_status, user_id, note, created_at | |
| shipments | business_id, id, order_id, courier, tracking_no, courier_charge, status, cod_expected, cod_collected, cod_status (none, with_courier, settled), settled_at | One order can have a second shipment after a failed first one |
| payments | business_id, id, order_id, method (cod, bkash, nagad, card, bank, cash), amount, reference, received_at, recorded_by | Advance payments and COD settlements |
| returns **new** | business_id, id, order_id, kind (failed_delivery, customer_return), reason, refund_amount, created_by | |
| return_items **new** | business_id, return_id, order_item_id, qty, disposition (restock, write_off) | |
| idempotency_keys **new** | business_id, key, request_hash, response_status, response_body, created_at | For POST order, payment and stock adjustment; kept 24 h |

Order totals: `total = subtotal − discount + delivery_charge`, `cod_amount = total − advance_paid`. Both are computed by the server; the client's numbers are ignored.

### 5.4 Billing

| Table | Key columns | Notes |
| --- | --- | --- |
| plans | id, code (trial, starter, pro), name_en, name_bn, price_monthly, is_public, limits (jsonb) | Limits keys: `variants`, `orders_per_month`, `staff_seats`, `import_rows`, `report_days`, `inbox_channels`, `ai_replies` |
| subscriptions | business_id, plan_id, status, current_period_start, current_period_end, trial_ends_at, grace_ends_at, pending_plan_id | One per business |
| usage_counters | business_id, metric, period (`2026-11`), used | Monthly counters only (orders, AI replies). Variant and seat counts are counted live. |
| ai_packs **new** (R2) | business_id, replies, used, expires_at, invoice_id | |
| invoices | id, business_id, number, kind (subscription, upgrade, ai_pack), plan_id, months, amount, vat_amount, status (open, paid, void, refunded), period_start, period_end, paid_at | |
| payment_attempts **new** | id, invoice_id, gateway (sslcommerz, manual), tran_id (unique), val_id, amount, status, raw (jsonb), validated_at | Every callback stored raw |
| billing_ledger **new** | id, business_id, invoice_id, type (payment, refund, adjustment), amount, gateway_ref, created_at | For monthly export to the accountant |

### 5.5 Platform

| Table | Key columns | Notes |
| --- | --- | --- |
| audit_logs | id, business_id (nullable), user_id, action, entity_type, entity_id, before, after (jsonb), ip, created_at | Written from events for: role changes, invites, price and cost changes, stock adjustments, order cancels and returns, settings, billing, admin actions |
| notifications **new** | business_id, id, user_id (nullable = all owners and managers), type, payload, read_at | In-app bell |
| uploads **new** | business_id, id, storage_key, content_type, size, purpose (product_image, logo, import), status | |
| scheduled_tasks | (db-scheduler's own table) | |
| event_publication | (Spring Modulith's own table) | |

---

## 6. Order and stock logic

Seven statuses: DRAFT, CONFIRMED, PACKED, SHIPPED, DELIVERED, RETURNED, CANCELLED. Stock effects below are for the default `stock_commit_point = SHIP`. With `CONFIRM`, confirming writes a SALE movement (on_hand −q, no reservation), shipping changes no stock, and cancelling a confirmed or packed order writes UNSALE +q instead of RELEASE.

| Transition | Who | Stock | Other effects |
| --- | --- | --- | --- |
| (new) → DRAFT | Staff, AI (R2) | None for staff drafts; AI drafts RESERVE for 30 min | |
| DRAFT → CONFIRMED | Staff | RESERVE each item | Fails with 409 and the short items if any `available < qty` |
| CONFIRMED → PACKED | Staff | None | Items locked after this |
| PACKED → SHIPPED | Staff | SHIP each item | Creates a shipment (courier, tracking no., charge); `cod_status = with_courier` |
| SHIPPED → DELIVERED | Staff | None | Records `cod_collected`. If less than expected, staff must record a partial return or a discount. |
| SHIPPED → RETURNED | Staff | Per item: RETURN or RETURN_DAMAGED | Failed delivery; creates a `returns` row |
| DELIVERED → DELIVERED (+ return) | Staff | Per returned item: RETURN or RETURN_DAMAGED | Partial delivery or later customer return. If every item comes back, the order moves to RETURNED. |
| DRAFT, CONFIRMED, PACKED → CANCELLED | Staff, owner | RELEASE what was reserved | Reason required |
| AI DRAFT → CANCELLED | Expiry job (R2) | RELEASE | After 30 min unconfirmed |

Rules the code enforces:

1. Each status change, its stock movements, the `stock_levels` update and the `order_events` row commit in **one transaction**, or none do.
2. Reserving locks the needed `stock_levels` rows with `SELECT … FOR UPDATE`, **always in variant-id order**, so two orders sharing variants cannot deadlock.
3. Items can be edited in DRAFT and CONFIRMED. In CONFIRMED an edit reserves or releases only the difference.
4. Each transition checks the current status inside the locked transaction plus the row `version`, so two staff clicking "ship" at once produce one shipment and one 409.
5. Order numbers come from `business_settings.next_order_number`, incremented with `UPDATE … RETURNING` inside the order transaction: per business, gap-free in practice, shown as `#1001`.
6. `payment_status` is derived from payments: `unpaid`, `partial` or `paid`. COD money is tracked on the shipment: expected → collected by courier → settled to the seller. Dashboard "COD outstanding" = collected but not settled, plus expected on shipments still in transit.
7. A batch endpoint marks many shipments' COD as settled at once, because couriers pay out in batches.
8. Low-stock check runs after every movement that lowers `available`; it creates one notification per variant per day at most.

**Concurrency test (part of B7's exit gate):** 20 threads confirm orders for the last 5 units of one variant. Exactly 5 succeed, `reserved = 5`, the ledger sums match.

---

## 7. API

REST under `/api/v1`, JSON, cookie session with CSRF token (double-submit cookie). Lists use cursor paging (`?cursor=…&limit=50`). Errors are Problem Details with a stable `code` (for example `stock.insufficient`) and a `detail` in the user's language, plus `errors[]` for field problems. Every write returns the new resource.

Endpoint groups (complete list will be in the OpenAPI spec):

| Group | Endpoints |
| --- | --- |
| Auth | `POST /auth/otp` (send), `POST /auth/signup`, `POST /auth/login` (password or OTP), `POST /auth/logout`, `POST /auth/password/reset`, `GET /me`, `PATCH /me` (name, locale), `POST /me/switch-business` |
| Business | `POST /businesses` (creates business + trial), `GET/PATCH /business`, `GET/PATCH /business/settings`, `POST /business/logo` |
| Staff | `GET /staff`, `POST /staff/invites`, `DELETE /staff/invites/{id}`, `POST /invites/{token}/accept`, `PATCH /staff/{userId}` (role), `DELETE /staff/{userId}` |
| Catalog | `GET/POST /categories`, `PATCH/DELETE /categories/{id}`, `GET /products?q=&category=&tag=&stock=low`, `POST /products`, `GET/PATCH /products/{id}`, `POST /products/{id}/archive`, `PUT /products/{id}/options` (regenerates the variant grid), `PATCH /variants/{id}`, `POST /uploads/presign`, `POST /products/{id}/images` |
| Stock | `POST /stock/adjustments` (one or many lines), `GET /variants/{id}/movements`, `GET /stock/low` |
| Import | `POST /imports` (from upload id), `PUT /imports/{id}/mapping`, `GET /imports/{id}/preview`, `POST /imports/{id}/commit`, `GET /imports/{id}`, `GET /imports/{id}/errors.csv`, `GET /imports/template.xlsx` |
| Customers | `GET /customers?q=` (name or phone), `POST /customers`, `GET/PATCH /customers/{id}`, `GET /customers/{id}/orders` |
| Delivery | `GET /geo/districts`, `GET /geo/districts/{id}/areas`, `GET/PUT /delivery-zones`, `GET /delivery-zones/quote?areaId=` |
| Orders | `GET /orders?status=&q=&from=&to=`, `POST /orders`, `GET/PATCH /orders/{id}`, `POST /orders/{id}/confirm`, `/pack`, `/ship`, `/deliver`, `/cancel`, `/returns`, `POST /orders/{id}/payments`, `GET /orders/{id}/events`, `POST /shipments/settle-cod` |
| Dashboard | `GET /dashboard/today`, `GET /reports/sales?from=&to=&groupBy=product\|day`, `GET /reports/profit`, `GET /exports/{orders\|products\|customers}.csv` |
| Billing | `GET /billing` (plan, status, usage), `GET /plans`, `POST /billing/checkout` (plan, months), `POST /billing/upgrade`, `POST /billing/downgrade`, `GET /billing/invoices`, `GET /billing/invoices/{id}` |
| Gateway callbacks | `POST /webhooks/sslcommerz/ipn`, `POST /payments/sslcommerz/{success\|fail\|cancel}` (redirect back to the app) |
| Notifications | `GET /notifications`, `POST /notifications/read` |
| Account data | `GET /business/export` (zip of CSVs, owner only), `DELETE /business` (owner, 7-day undo), `DELETE /customers/{id}` |
| Admin | `GET /admin/businesses?q=`, `GET /admin/businesses/{id}`, `POST /admin/businesses/{id}/suspend` and `/unsuspend`, `POST /admin/businesses/{id}/extend-trial`, `POST /admin/businesses/{id}/plan`, `POST /admin/invoices/{id}/mark-paid`, `GET/PUT /admin/plans`, `GET /admin/metrics` |

**Role matrix** (enforced in each controller method with `@PreAuthorize`, tested per endpoint):

| Capability | Owner | Manager | Staff |
| --- | --- | --- | --- |
| Billing, delete business, transfer ownership | ✓ | | |
| Staff invites and roles | ✓ | ✓ (cannot make Owner) | |
| Business settings, delivery zones | ✓ | ✓ | |
| Products: create, edit, price | ✓ | ✓ | |
| See cost price and profit | ✓ | ✓ | **hidden** (field left out of responses, not just the UI) |
| Stock adjustments | ✓ | ✓ | ✓ |
| Import | ✓ | ✓ | |
| Orders, customers, payments, returns | ✓ | ✓ | ✓ |
| Cancel a shipped order or delete a customer | ✓ | ✓ | |
| Dashboard | ✓ | ✓ | ✓ without profit |

---

## 8. Plans, trial and limits

**Plan rows at launch** (edit in admin):

| Limit | Trial (14 days) | Starter ৳990 | Pro ৳2,990 |
| --- | --- | --- | --- |
| variants | 5,000 | 500 | 5,000 |
| orders_per_month | unlimited | 2,000 | unlimited (fair use) |
| staff_seats | 10 | 3 | 10 |
| import_rows | unlimited | unlimited | unlimited |
| report_days | full | 90 | full + export |
| inbox_channels (R2) | 2 | 1 | 2 |
| ai_replies (R2) | 100 **(proposed cap, to protect AI cost)** | 0 | 1,000 |

**Subscription statuses and what each allows:**

| Status | Meaning | Allowed |
| --- | --- | --- |
| TRIALING | First 14 days | Everything, Pro limits |
| ACTIVE | Paid period running | Everything within plan limits |
| GRACE | Up to 7 days after the period ended unpaid | Everything, with a banner and daily reminder |
| READ_ONLY | Grace over, or trial over without payment | Read, export, pay. Every other write returns 402 `subscription.read_only`. |
| SUSPENDED | Admin action | Owner can log in to billing and support only |

A daily job moves businesses between these statuses and sends reminders 7, 3 and 1 day before the end. READ_ONLY businesses get warnings at 60 and 85 days; data is deleted at 90 days (job ships disabled until you approve the wording).

**Limit checks.** `Entitlements.check(business, metric)` runs inside the same transaction as the create. Monthly counters use `UPDATE usage_counters SET used = used + 1 WHERE … AND used < :limit RETURNING used`, so two parallel creates cannot both pass at the limit. At the limit the API returns 402 with `code = plan.limit_reached`, the metric and the limit, and the frontend shows the upgrade prompt. Existing data is never hidden.

Downgrade when usage is over the lower plan's limits: allowed, takes effect at renewal; creation is then blocked until usage is under the limit.

---

## 9. Payments (SSLCommerz)

Hosted checkout, prepaid 1, 6 or 12 months. The browser redirect is never trusted.

1. `POST /billing/checkout` creates an `open` invoice and a `payment_attempt` with a fresh `tran_id`, calls SSLCommerz's session API, returns `GatewayPageURL`.
2. The seller pays on SSLCommerz's page.
3. SSLCommerz calls our IPN endpoint. We store the raw body, then call the **Validation API** with `val_id`, and check status (`VALID` or `VALIDATED`), amount, currency `BDT` and `tran_id`.
4. In one transaction: lock the invoice, return early if already paid (idempotent on `tran_id`), mark paid, extend the subscription, write the ledger row, emit `InvoicePaid`.
5. The success redirect only shows "checking payment…" and polls `GET /billing/invoices/{id}`.
6. A job every 15 minutes asks SSLCommerz's transaction query API about attempts still pending after 30 minutes, in case an IPN was lost.

Upgrade mid-period: charge `(new price − old price) × days left ÷ days in period`, rounded up to whole taka; the plan switches when that invoice is paid; the period end does not change. Yearly price = 10 × monthly (placeholder). Manual bank transfer: admin marks the invoice paid with a reference; same code path from step 4.

VAT: one config value `billing.vat-rate` (0 until your accountant answers), shown on invoices. SaaS invoices and packing slips are rendered as HTML for the browser to print or save as PDF **(changed)**: Java PDF libraries draw Bangla conjunct letters wrongly, and the browser draws them correctly. Server-side PDF can come later through a headless Chrome service.

---

## 10. Background jobs

| Job | Schedule | Notes |
| --- | --- | --- |
| Import processing | On demand | Parse, validate, preview, commit in chunks of 500 rows |
| Image processing | On demand | Thumbnails 200 px and 800 px, WebP, EXIF stripped |
| Subscription lifecycle and reminders | Daily 09:00 Dhaka | Statuses in section 8 |
| Pending payment check | Every 15 min | Section 9 step 6 |
| Low-stock digest email | Daily 08:00 Dhaka | In-app alerts are immediate |
| Ledger reconciliation | Nightly | Section 5.2 |
| Old idempotency keys, OTPs, expired invites cleanup | Nightly | |
| Business data deletion | Daily | Off until approved |
| AI draft reservation expiry (R2) | Every minute | |
| Webhook processing, AI turns (R2) | On demand | |

---

## 11. Security checklist (built into the phases, reviewed in B13)

- Login, OTP send and OTP verify rate limits per phone and per IP; resend cooldown 60 s; lock after 10 failed passwords in 15 min.
- Session cookie: `HttpOnly`, `Secure`, `SameSite=Lax`; idle timeout 7 days; all sessions ended on password change; staff removal ends that user's sessions for that business at once.
- CSRF protection on all cookie-authenticated writes; CORS allows only the frontend origin.
- Owner TOTP two-factor (P1, B13); required for platform admins.
- Uploads: presigned URL limited to content type and size (5 MB images, 10 MB imports); the server re-checks the file's real type before use.
- Secrets (SSLCommerz keys; Meta tokens in R2) come from environment variables; Meta tokens are encrypted with AES-GCM using a key that is not in the database.
- Cost price never leaves the server for Staff (DTO level).
- Customer data export and deletion; data-deletion URL (needed for Meta in R2).
- Dependency scanning (Dependabot) and an OWASP check in CI.

---

## 12. Backend phases

Each phase is one reviewable commit (or a few lettered commits, as in the marketplace project), with tests, and ends with a short note on what to look at. Days are full-time working days; for part-time, double them.

| Phase | Builds | Days | Exit gate |
| --- | --- | --- | --- |
| B0 Skeleton | Repo layout, Maven, Spring Boot, Modulith verify, Flyway, three DB roles, embedded Postgres for tests and local runs, Problem Details, OpenAPI, JSON logs, health, GitHub Actions CI | 2 | CI green; app starts locally with `./mvnw spring-boot:test-run` |
| B1 Identity | Users, phone OTP (SMS port + log sender for dev), signup, login, logout, reset, sessions, rate limits, locale | 4 | Signup → login → `/me` works; OTP limits tested |
| B2 Tenancy | Businesses, settings, memberships, roles, invites, business switch, tenant context, RLS, audit log, **cross-tenant test harness** | 5 | Harness runs on every endpoint; RLS zero-row test passes |
| B3 Catalog | Categories, tags, products, options, variant grid, SKUs, images via presigned upload, search (`pg_trgm`) | 5 | Product with 3 sizes × 2 colors created and found by name and SKU |
| B4 Inventory | Ledger, stock levels, adjustments, history, low-stock alerts, nightly reconciliation | 3 | Ledger sums match cache after a randomised 1,000-movement test |
| B5 Import | Upload, column mapping, preview of 20 rows with errors, commit job, update-by-SKU, error CSV, template | 4 | A 2,000-row real seller sheet imports; bad rows reported, good rows kept |
| B6 Customers and delivery | Customers (phone dedupe), BD geo seed, delivery zones, charge quote | 2 | Order form data available: customer lookup by phone, area → charge |
| B7 Orders core | Create, edit, confirm with reservation, numbering, idempotency keys, events, cancel | 5 | Concurrency test in section 6 passes |
| B8 Fulfilment | Pack, ship (shipment), deliver, COD collected and settled, payments, returns with restock or write-off, partial delivery, printable packing slip | 5 | Every transition in section 6 has a test, including the stock and money results |
| B9 Dashboard and reports | Today panel, sales by product and day, profit, CSV exports, plan-based report range | 3 | Numbers match a hand-computed fixture |
| B10 Plans and limits | Plans table, trial on signup, subscription statuses, daily lifecycle job, entitlements, usage counters, read-only mode | 3 | Each limit and status has a test; parallel creates at the limit stop at the limit |
| B11 Payments | SSLCommerz checkout, IPN, validation, invoices, renewals, upgrade proration, manual payment, billing ledger and export | 4 | A sandbox payment extends a subscription end to end; replayed IPN changes nothing |
| B12 Platform admin | Business list and detail, usage, suspend, extend trial, plan edits, mark invoice paid, metrics | 2 | Admin actions audited; non-admins get 403 |
| B13 Hardening | Notifications (email templates bn/en), owner 2FA, Sentry, backups to R2 and restore drill, Jib image, Compose + Caddy, security review, load test | 3 | Staging server up; restore drill passes; security checklist ticked |
| **Total MVP backend** | | **≈ 51 days (10 weeks full-time, ~20 part-time)**; split into 33 commits in [implementation-plan.md](implementation-plan.md) | |

**Timeline note.** The product plan gives 14 full-time weeks to phases 1–3, frontend included, and puts the pilot at Feb 22, 2027. With this backend at about 10 weeks and part-time work, that date does not hold; a pilot around mid-2027 is more realistic. Worth settling before you tell pilot sellers a date.

**Release 2 backend (outline only, planned in detail after B13):**

| Phase | Builds |
| --- | --- |
| R1 Channels | Meta app config, Facebook Login for Business page connect, encrypted tokens, webhook endpoint with signature check, raw event store, dedupe, token-expiry handling |
| R2 Inbox | Conversations, messages, assignment, statuses, 24-hour window rules, Human Agent tag, SSE stream, staff send |
| R3 AI engine | Provider adapter, tools from the product plan (tenant bound from server context), debounce, gate, context builder, reply validator, handoff, `ai_runs` cost logging, AI quota and packs, 200-question eval runner |
| R4 AI drafts | `draft_order` with 30-minute reservation, expiry job, one-click confirm, `ai_auto_confirm` rules, review mode |
| R5 WhatsApp | Embedded Signup, templates, pricing webhooks, Meta usage display |
| R6 Courier and renewals | Courier booking adapters for the couriers you pick, status sync, bKash tokenized auto-renewal |

---

## 13. Answers (Oct 25, 2026)

1. **Stock timing:** option (a). Each seller picks `stock_commit_point` in settings; default is reserve at confirm, deduct at ship.
2. **Couriers:** courier name and tracking number typed by staff in the MVP; API booking in release 2.
3. **Trial end:** READ_ONLY when the trial ends unpaid; data kept 90 days.
4. **Java:** stay on 17.
5. **SMS gateway:** added later. Until then OTP goes by email, and the dev log sender prints codes locally.
6. **Company and SSLCommerz:** added later. Payments are built against the SSLCommerz sandbox.
7. **Name:** working name **Mojud** (মজুদ, "stock"), Java package `com.mojud`. Easy to rename before launch.
8. **Repo:** private GitHub repo `Al-Mahamud/online-seller-saas` with `backend/`, `frontend/` (later), `docs/`, `deploy/`.

The phase-by-phase breakdown is in [implementation-plan.md](implementation-plan.md).

## 14. Changes to the product plan, in one list

1. PostgreSQL-only infrastructure (db-scheduler, Bucket4j) instead of Redis + BullMQ.
2. Cookie sessions instead of access + refresh tokens.
3. SSE instead of Socket.IO for the inbox (release 2).
4. `stock_levels` table separate from variants; ledger with two delta columns so reservations are in it too.
5. Every product has at least one variant.
6. New tables: business_settings, invites, otp_challenges, product_options, product_images, delivery_zones, geo reference, returns, return_items, idempotency_keys, payment_attempts, billing_ledger, notifications, uploads.
7. `business_id` on child tables as well, with composite foreign keys.
8. Packing slips and invoices as browser-printed HTML instead of server PDFs (Bangla text rendering).
9. Trial AI replies capped at 100 (R2).
10. Currency column dropped (BDT only).
