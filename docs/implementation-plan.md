# Implementation Plan (Mojud backend)

The backend MVP is split into 13 phases and 33 sub-phases. Each sub-phase is one commit you can review in one sitting: about 1 to 2 full-time days of work, which is about 2 to 4 days part-time. Release 2 is outlined at the end and will be planned in detail after Phase 13.

Design details (tables, rules, endpoints) live in [backend-plan.md](backend-plan.md); this file says **in what order** they get built and **how each step is checked**. Section references like "§6" point to backend-plan.md.

## How every sub-phase works

- Ends with a green `./mvnw verify` (unit tests, integration tests on embedded PostgreSQL, Spring Modulith boundary check, cross-tenant suite once it exists).
- Comes with a short review note in the commit message: what changed, what to look at first, what was left out on purpose.
- Adds only the Flyway migrations it needs (`V<n>__<what>.sql`). A migration is never edited after its commit is pushed.
- Updates the status column below.
- The next sub-phase starts after you have reviewed (or said to continue).

## Status

| Phase | Sub-phases | Days | Status |
| --- | --- | --- | --- |
| 0 Foundation | 0a skeleton · 0b database and tenant plumbing | 3 | Done |
| 1 Identity | 1a sign-up and login · 1b account management | 4 | Done |
| 2 Tenancy | 2a businesses and tenant context · 2b staff and roles · 2c cross-tenant suite and audit log | 5 | Done |
| 3 Catalog | 3a categories and tags · 3b products and variants · 3c files, images, search | 5 | Done |
| 4 Inventory | 4a stock ledger and adjustments · 4b low-stock alerts and reconciliation | 3 | Done |
| 5 Import | 5a upload, mapping, preview · 5b commit and error report | 4 | Done |
| 6 Customers and delivery | 6a geo data and customers · 6b delivery zones | 2 | Done |
| 7 Orders | 7a drafts, totals, numbering · 7b confirm, reserve, cancel | 5 | Done |
| 8 Fulfilment | 8a pack, ship, deliver · 8b payments and COD · 8c returns · 8d packing slip | 5 | Done |
| 9 Reports | 9a today dashboard · 9b sales, profit, exports | 3 | Done |
| 10 Plans and subscriptions | 10a plans, trial, limits · 10b lifecycle and read-only mode | 3 | Done |
| 11 Billing payments | 11a SSLCommerz checkout · 11b upgrades, manual payments, ledger | 4 | Done |
| 12 Platform admin | 12a business management · 12b metrics and data rights | 2 | 12a done · 12b in review |
| 13 Launch readiness | 13a email and 2FA · 13b deployment and backups · 13c security review and load test | 3 | Not started |
| **Total** | **33 commits** | **≈ 51 days** | |

Days are full-time; double them for part-time.

### Dependency order

```
0a → 0b → 1a → 1b → 2a → 2b → 2c ─┬→ 3a → 3b → 3c → 4a → 4b → 5a → 5b
                                  └→ 6a → 6b ──────────────┐
                              4a + 6b ─→ 7a → 7b → 8a → 8b → 8c → 8d → 9a → 9b
                                               9b → 10a → 10b → 11a → 11b → 12a → 12b → 13a → 13b → 13c
```

Phase 6 can be done any time after 2c. Everything else runs in order.

---

## Phase 0: Foundation

### 0a: Project skeleton (1.5 days)

**Builds**
- Repo layout: `backend/`, `docs/`, `deploy/` (empty), `.gitignore`, `README.md`.
- Maven wrapper, Spring Boot 4.1.x, Java 17, package `com.mojud`.
- Empty module packages (`shared`, `identity`, `tenancy`, `catalog`, `inventory`, `importing`, `customers`, `delivery`, `orders`, `reporting`, `billing`, `notifications`, `files`, `audit`, `admin`), each with a `package-info.java` saying what it owns.
- `ModularityTests` (Spring Modulith `verify()`).
- Error handling: `ErrorCode`, `ApiException`, Problem Details handler for validation, business, not-found and optimistic-lock errors; messages in `messages_en.properties` and `messages_bn.properties`, chosen by `Accept-Language`.
- `Clock` bean in UTC; `shared.time.DhakaTime` helpers for "today" and "this month".
- Structured JSON logs, Actuator `/healthz`, springdoc at `/api/docs`.
- GitHub Actions: `./mvnw verify` on push and pull request; Dependabot for Maven and Actions.

**Checked by:** app boots; modularity test passes; one test per error type checks the problem response shape in both languages.

### 0b: Database and tenant plumbing (1.5 days)

**Builds**
- Embedded PostgreSQL (zonky) for tests and for `./mvnw spring-boot:test-run`.
- Roles `mojud_owner`, `mojud_app`, `mojud_system` (§4) in `db/bootstrap/roles.sql`, run once per server by a superuser (creating roles and `BYPASSRLS` need one, so this cannot be a Flyway migration). `V1__role_privileges.sql` grants data access to the two login roles. Flyway runs as owner; the app pool connects as `mojud_app`.
- `TenantAwareDataSource`: sets `app.business_id` on every connection checkout (empty when no tenant).
- `SystemDataSource` bean for `mojud_system`, plus an ArchUnit rule that only `..system..` packages may use it.
- `shared` types: `Money` (poisha `long`), `PhoneNumber` (BD E.164 parsing), UUID v7 id generator, cursor paging.
- `V2__rls_helpers.sql`: a SQL helper that enables RLS, forces it and creates the standard policy for a table in one call, so every later migration uses the same policy text.
- db-scheduler and the Spring Modulith event publication table (needed from Phase 2 onwards).

**Checked by:** a test table with RLS: rows inserted for tenants A and B; a connection set to A sees only A's rows; with no tenant set it sees none; `mojud_system` sees both.

---

## Phase 1: Identity

### 1a: Sign-up and login (2.5 days)

**Builds**
- `users`, `otp_challenges` tables.
- `SmsSender` and `EmailSender` interfaces, each with a logging implementation. With no SMS gateway, OTPs go by email; the phone is stored but marked unverified until the gateway is added.
- `POST /auth/otp`, `POST /auth/signup`, `POST /auth/login` (password, or OTP), `POST /auth/logout`, `GET /me`.
- Spring Session JDBC cookie session, CSRF (cookie-to-header), CORS for the frontend origin, Argon2id.
- Rate limits (an in-memory fixed-window limiter, as in the marketplace project, instead of Bucket4j): OTP send per phone/email and IP, verify attempts, login attempts (§11).
- Dev-only `GET /dev/otp` endpoint (test sources only) so local runs and later end-to-end tests can read codes.

**Checked by:** sign-up → login → `/me`; wrong code 5 times locks the challenge; expired code rejected; rate limits return 429 with `Retry-After`.

**Review fixes (after `/code-review`):** row lock on code checks (parallel guesses, double use); unverified phones and emails are not unique and cannot sign in (V5); failure-only sign-in limits per account and IP, cleared on success; per-IP sign-up limit; one database connection per request; unique-index races answered as 409; codes sent after commit on a background thread; session cookie renewed daily while active; no size cap on rate-limit counters.

### 1b: Account management (1.5 days)

**Builds**
- `PATCH /me` (name, locale), password change, `POST /auth/password/reset` by OTP.
- Password change or reset ends every other session of that user.
- Add or change email and phone, each confirmed by OTP.

**Checked by:** reset logs out other sessions; locale changes the language of error messages.

**As built:** the saved language is kept in the session and wins over `Accept-Language` on every device of the user. Changing a phone or email needs a code to the new one **and** the current password (a stolen session alone cannot move the account to an attacker's email). Endpoints: `PATCH /me`, `POST /me/password`, `POST /me/contact/code`, `POST /me/contact`, `POST /auth/password/reset` (code purpose `PASSWORD_RESET` via `POST /auth/otp`).

---

## Phase 2: Tenancy

### 2a: Businesses and tenant context (2 days)

**Builds**
- `businesses`, `business_settings`, `memberships`.
- `POST /businesses` makes the caller OWNER and selects the business in the session. (The trial subscription is added in 10a.)
- `GET/PATCH /business`, `GET/PATCH /business/settings` (including `stock_commit_point`), `POST /me/switch-business`.
- `TenantContext` filled from the session on each request; Hibernate `@TenantId` on tenant entities; RLS on the new tables.
- Role annotations (`@OwnerOnly`, `@ManagerOrAbove`, any member) built on `@PreAuthorize`.

**Checked by:** a user in two businesses sees each one's data only after switching; a non-member cannot switch to a business.

**As built:** roles are enforced by `@RequiresRole` (a Spring MVC interceptor, so a missing business answers `business.not_selected` rather than a bare 403) instead of `@PreAuthorize`. The membership is re-read on every request, so removals and role changes count at once. Listing a user's businesses uses the system role (`tenancy.system.MembershipDirectory`), because row-level security shows one business at a time. `GET /businesses` (the switcher) was added; an account may own at most 5 businesses. Order numbering is left out of `business_settings`: the orders module will own its counter (7a).

### 2b: Staff and roles (1.5 days)

**Builds**
- `invites`: `POST /staff/invites` (phone or email), `DELETE /staff/invites/{id}`, `POST /invites/{token}/accept` (signs up the invitee if needed).
- `GET /staff`, `PATCH /staff/{userId}` (role), `DELETE /staff/{userId}`, owner transfer.
- Removing a member or changing their role updates their live sessions at once.

**Checked by:** the role rules in §7 (a manager cannot create an owner; staff cannot invite); removed staff get 404 on their next request.

**As built:** accepting needs a signed-in account that has verified the invited phone or email (a new person signs up with that email first), so a forwarded link is useless to others. The link is `<web>/invites#<token>`: the token sits in the fragment and travels in request bodies, never in URLs that servers log. Preview is `POST /api/v1/public/invites/lookup` (new `/api/v1/public/**` area for open endpoints); accept is `POST /api/v1/invites/accept`. Ownership transfer is `POST /staff/{userId}/make-owner` with the owner's password. Members cannot remove themselves yet ("leave business" can come later).

### 2c: Cross-tenant suite and audit log (1.5 days)

**Builds**
- The cross-tenant test harness from §4: seeds tenants A and B, lists every endpoint, calls each as B with A's ids, and fails when an endpoint has no case.
- `audit_logs` and an `AuditRecorder` that listens to module events (role changed, invite sent, settings changed). Later phases add their own events.
- `GET /business/audit-log` (owner).

**Checked by:** the harness itself (it fails on purpose if a test endpoint skips the tenant check); audit rows written for 2a and 2b actions.

**As built:** `CrossTenantTests` holds one case per endpoint (`"METHOD /path"`), each run by business B's owner against business A; endpoints without business data are listed with a reason. `CrossTenantSuiteSelfTest` adds a deliberately leaky endpoint and checks the suite reports both the missing case and the leak. Modules write audit entries through `shared.audit.AuditTrail` (a generic `AuditEvent`, so modules need not know the audit module) inside the transaction of the change; the `audit` module writes them on the same connection. `audit_logs` is append-only (UPDATE and DELETE revoked). The plan's "audit log of platform admin actions" will be a separate table in 12a, since every entry here belongs to one business.

---

## Phase 3: Catalog

### 3a: Categories and tags (1 day)

**Builds**
- `categories` (two levels), `tags`, `product_tags`; create, rename, reorder, delete (only when empty).

**Checked by:** a third level is rejected; deleting a non-empty category returns 409.

**As built:** `product_tags` moves to 3b with products, and "empty" means "no subcategories" until 3b adds "no products". Categories can also be moved between groups (`POST /categories/{id}/move`); a group is reordered by listing all of it (`PUT /categories/order`). Names are trimmed, spaces collapsed and Unicode-normalised (NFC), so the same Bangla word typed two ways is one name. A subcategory's parent must be in the same business: a composite foreign key `(business_id, parent_id)`, the first of the composite keys from backend-plan.md §4. Catalog changes are not audited (not in the §11 list).

### 3b: Products and variants (2.5 days)

**Builds**
- `products`, `product_options`, `product_variants`, `stock_levels` (empty rows created with each variant; ledger comes in 4a).
- Create a product with options (e.g. Size: S, M, L; Color: Black, White) and get the variant grid; products without options get one default variant.
- Edit variant price, cost, SKU (auto-generated if left blank), threshold; archive and restore products.
- `PUT /products/{id}/options` adds or removes grid rows; a variant that was ever sold is archived, never deleted.
- Cost price and margin removed from responses for STAFF (separate response views).
- Optimistic locking (`version`) on products and variants.

**Checked by:** 3 × 2 grid gives 6 variants with unique SKUs; duplicate SKU in the same business returns 409 but is allowed in another business; staff response has no `costPrice` field.

**As built:** `stock_levels` moves to 4a (it belongs to the inventory module). Variants are never deleted: combinations that leave the grid are archived and restored with SKU and prices when they return; a new option gives existing variants its first value, so they keep SKU, prices (and later stock); an option renamed by `id` keeps its variants; a renamed *value* is treated as remove + add. Up to 3 options × 30 values, 100 variants. Money in the API is whole poisha. Responses carry `version`; edits that send a stale one get 409. Category and tag references are checked by composite foreign keys (another business's id → 404); a category with products cannot be deleted; deleting a tag removes it from products. Price and cost changes are audited (`variant.prices_changed`). Single variants can be archived and restored. Search (`q`) comes with 3c.

### 3c: Files, images and search (1.5 days)

**Builds**
- `files` module: `FileStorage` interface with local-disk (dev) and S3/R2 implementations; `uploads` table; `POST /uploads/presign`.
- Image job (db-scheduler): checks the real file type, strips EXIF, makes 200 px and 800 px WebP.
- `product_images`: attach, reorder, set per variant; 8 per product.
- Business logo upload.
- Search: `pg_trgm` index on product name and SKU; `GET /products?q=&category=&tag=&status=`.

**Checked by:** a PNG renamed `.jpg` is accepted as PNG and an `.exe` renamed `.jpg` is rejected; search finds "kurti" from "kurt" and finds Bangla names.

**As built:** uploads are plain multipart posts to `POST /uploads` (up to 8 MB), processed in the request (no job needed for one image), rather than presigned uploads: simpler, and the server sees the bytes to check them. Images are stored as JPEG (PNG when transparent) at 800 px and 200 px: the JDK cannot write WebP, but WebP uploads are read (TwelveMonkeys). Storage is local disk; the S3/R2 adapter moves to 13b, when there is a bucket to test against. Files are served by the API to members of the owning business only (`GET /files/{id}/large|small`). Search with `q` returns the best 50 matches (substring of name or SKU, or name trigram word similarity ≥ 0.4 for typos); typo tolerance works for Latin text, Bangla matches by substring. Uploads that are never used are kept for now; a clean-up job can come later.

---

## Phase 4: Inventory

### 4a: Stock ledger and adjustments (2 days)

**Builds**
- `stock_movements` (append-only: `mojud_app` gets no UPDATE or DELETE on it).
- `StockService` public API: `receive`, `adjust`, `damage`, `reserve`, `release`, `ship`, `sell`, `unsell`, `restock`, `writeOffReturn`. Each locks `stock_levels` rows in variant-id order and writes movements and the cache in the caller's transaction.
- Opening stock on product create.
- `POST /stock/adjustments` (several lines at once, reason required, idempotency key), `GET /variants/{id}/movements`.
- Audit events for adjustments.

**Checked by:** a randomised test of 1,000 mixed movements: the cache equals the ledger sum and never goes negative; a release larger than the reservation is rejected.

**As built:** `stock_levels` (from 3b) arrives here; rows are created on a variant's first movement. Movements store the level after them, so history needs no summing. Adjustment kinds are RECEIVE, DAMAGE and COUNT (set on hand to the counted total); damage and counts cannot touch reserved stock. The `Idempotency-Key` mechanism (`shared.idempotency.Idempotency`) is built here, for later reuse by orders and payments. The variant history is served by the catalog (`GET /variants/{id}/movements`) using `StockQueries`, so inventory never reads catalog tables. Old idempotency keys are cleaned up with 4b's nightly job. Open point for 4b: the low-stock threshold lives on the catalog's variant, which inventory may not read; 4b decides how alerts get it.

### 4b: Low-stock alerts and reconciliation (1 day)

**Builds**
- Minimal `notifications` module: `notifications` table, `GET /notifications`, `POST /notifications/read`.
- Low-stock check after every movement that lowers availability; at most one alert per variant per day. `GET /stock/low`.
- Nightly reconciliation job (as `mojud_system`): compares ledger sums with `stock_levels` and reports mismatches.

**Checked by:** an alert appears when available reaches the threshold and does not repeat that day; a deliberately broken cache row is reported.

**As built:** the alert level stays on the catalog's variant (or the business default), so the catalog raises the alert: inventory publishes `StockChanged` (available before and after, per variant) inside the change's transaction and knows nothing of products. Alerts go to managers and owners, at most one per variant per Dhaka day (a unique dedupe key). Notifications are per business with an audience role; "read" is kept per member; titles are written at read time in the reader's language. `GET /stock/low` is served by the catalog. Nightly jobs (db-scheduler, Dhaka time, system role): stock reconciliation at 02:30 (logs mismatches as errors, fixes nothing) and idempotency-key clean-up at 03:00 (older than a day).

---

## Phase 5: Import

### 5a: Upload, mapping and preview (2 days)

**Builds**
- `import_jobs`, `import_rows`.
- Parse `.csv` (UTF-8 and UTF-8 with BOM, Bangla text) and `.xlsx` (streaming, first sheet).
- Column mapping with automatic guesses from header names in English and Bangla; `PUT /imports/{id}/mapping`.
- Row validation: required fields, numbers, duplicate SKUs within the file, variant attributes, category names.
- `GET /imports/{id}/preview`: first 20 rows with their errors, and totals of valid and invalid rows.
- `GET /imports/template.xlsx`.

**Checked by:** fixtures with a messy real-world sheet (merged headers, blank rows, "৳1,200" prices, Bangla digits).

**As built:** `POST /imports` takes the file itself (multipart, up to 10 MB), not an upload id: `/uploads` is for images, and the file is read at once and only its non-blank rows kept (as text, by sheet row number), so the file is not stored. Limits: 10,000 rows, 100 columns, and a .xlsx may unpack to at most 200 MB. The header row is the first of the top 10 rows naming two known fields (so a title or merged heading above is skipped) and can be changed with the mapping. Rows with the same product name are variants of one product, told apart by the option columns (up to 3). Unknown categories are not errors: they are listed as `newCategories` ("Name" or "Parent > Name") and made on commit. Rows whose SKU exists show as UPDATE. The catalog's limits moved to the public `catalog.CatalogLimits`, and `catalog.CatalogLookup` gives the importer categories and taken SKUs, so a sheet is checked exactly like the forms. Every mapping change re-checks all rows (10,000 rows: about 0.3 s); upload and check about 1 s. Old `.xls` files and non-UTF-8 CSVs are refused with a message saying how to save them.

### 5b: Commit and error report (2 days)

**Builds**
- `POST /imports/{id}/commit` queues a job; rows processed in chunks of 500, each chunk in its own transaction.
- Existing SKUs update price, cost and name; new SKUs create products and variants; stock column writes OPENING (new) or ADJUST (existing) movements tagged with the import job.
- `GET /imports/{id}` (progress), `GET /imports/{id}/errors.csv`.
- Rows over the plan's `import_rows` limit are refused (limit check is wired up in 10a).

**Checked by:** a 2,000-row file imports with 37 bad rows reported and the rest kept; importing the same file twice changes nothing the second time.

**As built:** the commit re-checks every row (the catalog may have changed since the preview), refuses an import with nothing valid, and starts the job once it commits, on the importer's own two threads (never the after-commit pool that sends sign-in codes); tests run it inline. Rows of one product go together through the catalog's public `CatalogImport` port, which finds the product by any of its SKUs, else the one product on sale with that name (so rows without SKUs do not duplicate on re-import), else creates it; it changes only what differs and adds new variants (with their option values) to existing products. Stock: OPENING for new variants, an ADJUST to the sheet's figure for existing ones, none when it already matches; movements carry the import and the committing member. Products are grouped into chunks of about 500 rows, each chunk one transaction with its rows' results; a failing chunk is retried product by product, so one bad product fails only its own rows. A sweeper (every minute, system role) starts queued jobs whose start was lost and resumes jobs whose heartbeat stopped for 5 minutes, skipping rows already done. When done: an "import finished" notification for managers and owners, and an `import.committed` audit entry. `errors.csv` holds every row with a problem (left out or failed) with its cells under the sheet's headers, so it can be fixed and uploaded again. 2,000 rows take about 4 s. Plan row limits arrive with billing (10a).

---

## Phase 6: Customers and delivery

### 6a: Geo data and customers (1 day)

**Builds**
- `divisions`, `districts`, `areas` seeded in English and Bangla (source dataset and its licence noted in the migration).
- `customers`: unique phone per business; search by phone or name; `GET /customers/{id}/orders` added in 7a.

**Checked by:** "01712-345678", "+8801712345678" and "8801712345678" match the same customer.

**As built:** geo data from github.com/nuhil/bangladesh-geocode (MIT; commit and licence notice in V18): 8 divisions, 64 districts, 494 upazilas, with the dataset's ids. The dataset has only Dhaka's rural upazilas, so the 50 Dhaka Metropolitan Police thanas are added as areas of Dhaka district (kind METRO_THANA, ids 9001-9050); their Bangla spellings are ours and worth a native check. "Coxsbazar" is corrected to "Cox's Bazar". Geo tables are read-only for the app and held in memory (`delivery.GeoDirectory`, public); `GET /geo/divisions`, `/geo/districts?divisionId=`, `/geo/districts/{id}/areas` need sign-in only. Customers: unique E.164 phone per business (a clash answers 409 `customer.phone_taken` with the existing `customerId`); `GET /customers?phone=` finds the one customer for a number written any way (order form); `?q=` searches part of a name (with small typos) or part of a number (any digits, Bangla too); an area brings its district, and a composite foreign key keeps the area in the district. Any member may read and edit customers; deleting comes with account-data work.

### 6b: Delivery zones (1 day)

**Builds**
- `delivery_zones` with two defaults made for each new business (Inside Dhaka ৳70, Outside Dhaka ৳130); `GET/PUT /delivery-zones`.
- `GET /delivery-zones/quote?areaId=` and the public `DeliveryCharges.quote()` API used by orders (and the AI later).

**Checked by:** every district matches exactly one zone; an unmatched area falls back to the default zone.

**As built:** zones list districts and, more narrowly, areas (so "Dhaka city" and "Savar, Keraniganj" can differ); an address takes its area's zone, else its district's, else the default. Districts and areas are linked through `delivery_zone_districts` and `delivery_zone_areas`, whose primary keys keep each in at most one zone of a business; a partial unique index keeps one default. `PUT /delivery-zones` replaces the whole set (ids sent back are kept; names may be swapped in one change) under a per-business lock, and is audited. Zones come from a new `tenancy.BusinessCreated` event, handled in the creating transaction; businesses from before 6b have none (none existed outside tests). Staff read zones and quotes; managers and owners change them. The check runs over all 64 districts and every area.

---

## Phase 7: Orders

### 7a: Drafts, totals and numbering (2.5 days)

**Builds**
- `orders`, `order_items`, `order_events`, `idempotency_keys`.
- `POST /orders` (customer by id, or new customer by phone; items; discount; delivery charge from quote, editable; advance paid; note), status DRAFT.
- Server-computed totals (§5.3); name, price and cost copied onto items; delivery address copied onto the order.
- Order numbers per business (`#1001`) from `business_settings`.
- `GET /orders` with filters (status, date range, phone, number), `GET /orders/{id}`, `PATCH /orders/{id}` in DRAFT.
- `Idempotency-Key` header support (shared filter, also used by stock adjustments and payments).

**Checked by:** totals ignore client numbers; the same idempotency key returns the first response; 50 parallel creates get 50 distinct numbers.

**As built:** `idempotency_keys` and the `Idempotency` helper came with 4a; orders use them. Numbers come from `order_counters` (owned by orders, as V7 intended) with an upsert that returns the previous value, so parallel orders queue on the row and a rollback returns its number; labels are "#1001" or the business's prefix ("RK-1001"), fixed when the order is made. Orders copy the customer (name, phone) and the delivery details (name, phone, address, district, area); each delivery field left out comes from the customer. A new customer by phone is found or made through the public `customers.CustomerDirectory`; items come from `catalog.CatalogLookup.variants` (name, option label, SKU, price, cost; archived products refused); an item may carry its own price. The delivery charge is the zone's unless given; a draft edit that moves the place re-quotes it. The database repeats the money arithmetic in CHECKs. Staff do not see unit costs. Lists filter by status, Dhaka days, phone (customer's or delivery, written any way) and number ("1001", "#1001", "RK-1001"). Delivery addresses are optional on drafts; 7b requires them to confirm. Customers' order counts are updated from 7b (on confirmation).

### 7b: Confirm, reserve and cancel (2.5 days)

**Builds**
- `OrderTransitions` service: the only place that changes status (controllers never set it).
- Confirm: reserve (or SALE, when `stock_commit_point = CONFIRM`); 409 `stock.insufficient` listing the short items.
- Editing items in CONFIRMED reserves or releases only the difference.
- Cancel from DRAFT, CONFIRMED or PACKED with reason; releases (or UNSALE).
- Order events and audit for every transition.

**Checked by:** the concurrency test from §6 (20 threads, 5 units, exactly 5 succeed) in both stock modes; two parallel cancels give one cancel and one 409.

**As built:** orders record what they did to stock (`stock_state`: NONE, RESERVED, DEDUCTED), so a cancel or an edit undoes exactly that even after the business switches `stock_commit_point`. Transitions lock the order row (`SELECT … FOR UPDATE`) before reading its status: a second cancel waits and then gets 409 `order.invalid_transition` (the row version stays as a second guard; the test fails without the lock). Confirmation needs a delivery address and district. Shortages name the items (product, option label, SKU, requested, available). Confirmed orders can be edited: only the difference moves stock, with every involved variant locked first in variant-id order (`StockService.lockAll`), and the customer can no longer change. Customers' `order_count` counts confirmed orders (a cancel takes one back) and `last_order_at` is set at confirmation, by atomic updates that leave the customer's version alone.

---

## Phase 8: Fulfilment

### 8a: Pack, ship and deliver (1.5 days)

**Builds**
- `shipments`; transitions CONFIRMED → PACKED → SHIPPED → DELIVERED.
- Ship takes courier name (free text with suggestions from past shipments), tracking number and courier charge; writes SHIP movements in `SHIP` mode.
- Bulk actions: pack or ship many orders at once (each order its own transaction, results reported per order).

**Checked by:** stock after ship in both modes; a second ship of the same order returns 409.

**As built:** shipping is allowed from CONFIRMED as well as PACKED (packing is an optional step). Stock follows the order's `stock_state`: RESERVED becomes SHIP movements; DEDUCTED moves nothing. A shipment keeps the courier (free text; suggestions are the business's own couriers, most used first, then well-known ones), tracking number (optional), the courier's charge to the business (not part of the order's total), and `cod_expected` (the order's COD amount; `cod_status` NONE when nothing is to be collected). A partial unique index allows one IN_TRANSIT shipment per order, leaving room for re-shipment in 8c. Deliver marks the shipment delivered; what the courier collected comes in 8b. Bulk pack and ship run each order in its own transaction and report each result (with a message in the reader's language); one courier for a bulk ship, with each order's tracking number and optionally its own charge.

### 8b: Payments and COD (1.5 days)

**Builds**
- `payments`: `POST /orders/{id}/payments` (advance by bKash, Nagad, bank, cash).
- Derived `payment_status` (unpaid, partial, paid); `cod_amount` recalculated when an advance is added.
- Deliver records `cod_collected`; shipment `cod_status` with_courier → settled.
- `POST /shipments/settle-cod` for a batch from one courier.

**Checked by:** money tables for the main cases: full COD, advance + COD, delivery charge paid in advance, collected less than expected.

**As built:** `payments` holds money paid to the business directly (bKash, Nagad, Rocket, bank, cash, card, other) with a reference; the order's `advance_paid` is their sum before shipping, so `cod_amount` drops as advances come in. An advance given when creating the order becomes its first payment; PATCH no longer accepts `advancePaid`. Payments are taken in DRAFT, CONFIRMED and PACKED (advances) and DELIVERED (a shortfall), never above what is owed, and not while the courier has the parcel. Deliver takes `codCollected` (default: the shipment's `cod_expected`; at most that). `orders.amount_paid` (advances + COD collected + later payments) gives `paymentStatus` (UNPAID, PARTIAL, PAID) and `balanceDue`; a shortfall stays visible until paid. COD settlement lives on shipments (`cod_status` WITH_COURIER to SETTLED, `settled_at`) rather than as payment rows: `POST /shipments/settle-cod` (managers and owners) settles one courier's delivered shipments all or none, with rows locked so each is settled once; `GET /shipments?status=&codStatus=&courier=` lists what couriers owe or still carry. Payments cannot yet be voided (a later phase, if needed).

### 8c: Returns and partial delivery (1.5 days)

**Builds**
- `returns`, `return_items`.
- Failed delivery: SHIPPED → RETURNED with per-item restock or write-off.
- Partial delivery: DELIVERED with a return for refused items and a lower `cod_collected`.
- Customer return after delivery; all items back moves the order to RETURNED.
- Re-ship after a failed delivery: new shipment on the same order (stock taken again).

**Checked by:** stock and money results for each return path; returning more than was shipped is rejected.

**As built:** `returns` (kind, reason, credit, optional refund with method and reference) and `return_items` (per order item: quantity and RESTOCK or WRITE_OFF, so one item can be split). Every return belongs to the shipment the goods came back from, and an item never comes back more than that shipment carried. Money: a return gives a **credit** (what the customer no longer owes): a failed delivery credits the whole order; refused or returned items credit their price, at most the goods' value after discount (the delivery charge stays owed). `netTotal = total − returnCredit`, `balanceDue = netTotal − amountPaid`; below zero the payment status is REFUND_DUE. A refund given with a return lowers what was paid; before delivery it lowers the advance too, so a re-shipment collects it again. Paths: `POST /orders/{id}/returns` FAILED_DELIVERY (SHIPPED to RETURNED; items left out all go back on the shelf; the shipment is RETURNED with nothing collected) or CUSTOMER_RETURN (from DELIVERED; everything back makes the order RETURNED); partial delivery is `POST /orders/{id}/deliver` with `refused` items (refusing everything is refused: record a failed delivery), and the courier's collection defaults to and is capped by what is still owed. A failed delivery can be shipped again (stock taken again with SALE, credit reset, a new shipment collects what is owed); a customer return cannot.

### 8d: Packing slip and invoice (0.5 days)

**Builds**
- `GET /orders/{id}/slip` and `GET /orders/print?ids=` return print-ready HTML (Bangla or English, business logo, COD amount in large type), for the browser to print or save as PDF.

**Checked by:** snapshot test of the HTML in both languages.

**As built:** server-rendered HTML (no template engine), one A5 page per order: business name, phone, address and logo (embedded as a data URI, so a saved PDF keeps it), order number and date, the cash to collect in large type (or "paid in full: collect nothing"), delivery details with area and district in the page's language, courier and tracking number, items with SKU, the money, the note, and the business's invoice footer. Bangla pages use Bangla digits; amounts use lakh grouping in both languages (12,34,567). `lang=bn|en` overrides the reader's language; `autoprint=true` adds the one script that opens the print dialog. These answers carry their own Content-Security-Policy (inline styles, data: images, that script by hash, framing by the same origin only) instead of the API's `default-src 'none'; sandbox`. Snapshots live in `src/test/resources/snapshots` (missing ones are written and the test fails until they are read and committed; `-DupdateSnapshots=true` rewrites them); they were also checked rendered in headless Chrome.

---

## Phase 9: Reports

### 9a: Today dashboard (1.5 days)

**Builds**
- Report query services inside `orders` and `inventory`; `reporting` combines them.
- `GET /dashboard/today`: orders and sales value confirmed today, orders by status, shipped today, delivered today, COD with courier and expected, low-stock count, top 5 products today. Profit figures only for owner and manager.

**Checked by:** a fixture day with known numbers, including returns and cancellations.

**As built:** `orders.OrderReports` and `catalog.CatalogReports` (public) answer their own figures; `reporting.Dashboard` combines them for `GET /dashboard/today?date=` (a past Dhaka day may be asked for). Definitions: *confirmed* = orders confirmed that day and not cancelled, valued at what the customer owes after returns (a failed delivery counts ৳0); *byStatus* = open orders (DRAFT to SHIPPED) however old, finished ones (DELIVERED, RETURNED, CANCELLED) finished that day; *shipped* counts shipments (re-ships too); *COD* is where couriers' cash is now (on the road, collected and not paid out), not tied to the day; *top products* by quantity kept (ordered less returned), grouped by the product name on the order; *profit* (managers and owners) = goods after discount and returns, less cost (restocked items' cost comes back, written-off items stay a loss), with `costMissing` when an item has no cost price. Inventory had no figure of its own here: low stock is the catalog's count.

### 9b: Sales, profit and exports (1.5 days)

**Builds**
- `GET /reports/sales?from=&to=&groupBy=day|product|category`, `GET /reports/profit` (item margin less discounts and write-offs; delivery margin shown separately).
- CSV exports of orders, products (with stock) and customers, streamed.

**Checked by:** report numbers equal a hand calculation on the fixture; exports open correctly in Excel with Bangla text (UTF-8 BOM).

**As built:** `GET /reports/sales?from=&to=&groupBy=day|product|category` (any member; days default to the last 30, at most 366, none in the future; every day of the range is listed). Days give orders, items kept, revenue (goods after discount and returns) and delivery charged; products and categories give items kept at their order prices (order discounts are not split over items), with the category from the catalog (`CatalogReports.categoryPaths`) since orders do not read catalog tables. `GET /reports/profit` (managers and owners): revenue, cost of goods kept, write-offs, gross profit; delivery charged less courier charges (all shipments, failed ones too) as the delivery margin; net profit; items without a cost price counted. CSV exports (managers and owners) live in the module owning the data: `/exports/orders.csv?from=&to=` (orders), `/exports/products.csv` (catalog), `/exports/customers.csv` (customers); `shared.csv.CsvWriter` writes UTF-8 with BOM and CRLF, quotes as needed, defuses formula-like text with an apostrophe, and money as taka with two decimals; phones as "01712-345678" so Excel keeps the leading zero. Rows are read in batches (fetch size 500) and written as they come. The products export uses the import template's columns in the reader's language, and imports back unchanged. Plan-based report ranges arrive with billing (10a). Not opened in real Excel here (none on this machine): the format is checked byte by byte.

---

## Phase 10: Plans and subscriptions

### 10a: Plans, trial and limits (1.5 days)

**Builds**
- `plans` seeded with Trial, Starter and Pro (§8); `subscriptions`; `usage_counters`.
- Trial subscription created with each new business (14 days); one trial per owner phone.
- `Entitlements` API, wired into: variant creation and import (variants), order creation (orders per month), invites (seats), import size, report date range, exports.
- 402 `plan.limit_reached` with metric and limit.
- `GET /billing` (plan, status, usage), `GET /plans`.

**Checked by:** each limit hit exactly at its value; parallel creates at the limit never pass it; existing data stays readable over the limit.

**As built:** `plans` (read-only for the app) hold limits as JSON with a null for unlimited, plus an `exports` flag (Pro and the trial: "full + export"). Every new business gets a subscription in its creating transaction (from `tenancy.BusinessCreated`): a 14-day TRIAL (Pro limits) when its owner has not had one, keyed by the owner's *verified* phone (an unverified one could be someone else's) or else their account (`trial_claims`); otherwise it starts READ_ONLY (enforced in 10b). `billing.Entitlements` (public) answers limits in the transaction of the change; over a limit: 402 `plan.limit_reached` with `metric` and `limit`. Variants count those on sale (active variants of active products): a catalog change (create, options, restoring a product or variant, import) is refused only when it leaves more on sale than before and more than the limit, so archiving makes room and a business over its limit keeps everything; these changes run under the catalog's per-business lock. Orders per month: a conditional `UPDATE usage_counters` at order creation (an idempotent resend is not counted again). Staff seats: staff and managers plus open invites, owner excluded, checked at invite and at accept under a per-business seat lock; tenancy asks through `tenancy.SeatPolicy`, which billing implements, so tenancy does not depend on billing. Import rows checked at commit (unlimited on every plan for now). Report days (Starter 90) bound reports and the dashboard's date. `GET /billing` (managers and owners: plan, status, trial end, usage), `GET /plans` (public plans). Live counts reach billing through `billing.UsageSource` (the catalog counts variants) and `tenancy.TeamSize`.

### 10b: Lifecycle and read-only mode (1.5 days)

**Builds**
- Daily job (09:00 Dhaka): TRIALING/ACTIVE → GRACE → READ_ONLY; reminders 7, 3 and 1 day before the end (in-app now, email in 13a).
- Read-only filter: writes return 402 `subscription.read_only`, except billing, logout, profile and exports.
- SUSPENDED handling.
- Downgrade scheduled for renewal (`pending_plan_id`).
- Data-deletion job for READ_ONLY over 90 days, shipped **disabled**.

**Checked by:** a test clock moved through trial → grace → read-only, checking allowed and blocked calls at each step.

**As built:** the daily job (`billing.system.SubscriptionJobs`, 09:00 Dhaka) finds subscriptions with the system role and moves each as its own business (`billing.SubscriptionLifecycle`, row locked): an ended trial goes straight to READ_ONLY (nothing was paid, so no grace); an ended paid period goes to GRACE for 7 days with a daily reminder, then READ_ONLY. Reminders 7, 3 and 1 day before a trial or period ends; warnings 60 and 85 days into READ_ONLY. All are in-app notifications to the owner (email in 13a), at most one of each a day, so a second run the same day changes nothing; changes are audited as `subscription.changed`. The read-only guard (`billing.SubscriptionGuard`, an interceptor after tenancy's role check) applies to the business's own endpoints (those with `@RequiresRole`): READ_ONLY refuses every write with 402 `subscription.read_only`; SUSPENDED refuses everything with 402 `subscription.suspended`; billing and notifications stay open; sign-in, profile, switching or creating businesses and accepting invites are not business endpoints and are unaffected; exports are reads. `PUT /billing/plan` (owner): a cheaper plan waits for the renewal (`pending_plan_code`, applied by renewal in Phase 11), the current plan cancels a waiting change, anything needing payment answers 409 `billing.payment_needed` until Phase 11. The 90-day data deletion job ships disabled (`mojud.billing.data-deletion.enabled`) and, until account deletion exists (12), only lists the businesses due. An owner's second business (no trial) starts READ_ONLY.

---

## Phase 11: Billing payments

### 11a: SSLCommerz checkout (2 days)

**Builds**
- `invoices`, `payment_attempts`.
- `SslCommerzClient` (session, validation and transaction query APIs) with sandbox config; credentials from environment variables.
- `POST /billing/checkout` (plan, 1/6/12 months) → gateway URL.
- IPN endpoint as `mojud_system`: store raw body, validate, check amount/currency/tran_id, mark paid once, extend the subscription (§9).
- Success/fail/cancel redirect endpoints back to the frontend.
- Pending-payment check job every 15 minutes.

**Checked by:** tests with a fake SSLCommerz server: a valid payment extends the period; a replayed IPN changes nothing; a wrong amount is refused; a forged IPN fails validation. One manual end-to-end run against the real sandbox once you add credentials.

**As built:** `POST /billing/checkout {planCode, months: 1|6|12}` (owner) commits an OPEN invoice (`MJ-000001`, price × months, a year for ten months, plus `mojud.billing.vat-rate`, 0) and a PENDING `payment_attempts` row with a fresh `tran_id`, then calls the session API and returns `GatewayPageURL`; a refusal marks the attempt FAILED and answers 502 `billing.gateway_error`; with no credentials, 503 `billing.gateway_unavailable`. While a paid period runs, checkout extends the same plan only (another plan: 409 `billing.use_upgrade`, 11b). `billing.gateway.SslCommerzClient` reads its settings on each call (`mojud.billing.sslcommerz.*`, credentials from `SSLCOMMERZ_STORE_ID` / `SSLCOMMERZ_STORE_PASSWORD`, sandbox by default). Callbacks live under `/api/v1/public/gateway/` (no session, no CSRF token): each is stored raw in `gateway_callbacks` (system role only) first; the IPN finds the attempt by `tran_id` (system role), asks the Validation API about the `val_id`, and applies the answer as the business: invoice row locked, early return if already PAID, a mismatch (status, `tran_id`, currency BDT, amount) marks the attempt INVALID; otherwise attempt PAID, subscription extended (`Subscriptions.extend`: after a running period or a running trial, else from now; ACTIVE; grace, read-only and a waiting downgrade cleared), invoice PAID with its period, audit, owner notification and an `InvoicePaid` event. The browser's success/fail/cancel return only stores the call and redirects (303) to the web app (`/billing/return?result=&invoice=`), which polls `GET /billing/invoices/{id}`. `billing.system.PendingPayments` (every 15 minutes) queries attempts pending over 30 minutes and gives up after 2 days. Ledger rows and receipts come with 11b. **Not yet run against the real sandbox:** waiting for store credentials.

### 11b: Upgrades, manual payments and ledger (2 days)

**Builds**
- Upgrade with proration; downgrade at renewal; yearly price rule.
- Manual (bank transfer) payment marked by admin, through the same "mark paid" code path.
- `billing_ledger`, `GET /billing/invoices`, printable invoice HTML, monthly ledger CSV for the accountant, `billing.vat-rate` config.
- Email/in-app receipt event.

**Checked by:** proration on fixed dates (day 1, mid-month, last day); ledger total equals paid invoices.

**As built:** `POST /billing/upgrade {planCode}` (owner, a running paid period, a dearer plan): `billing.Proration` charges (new − old price for the period as paid, a year at the yearly price) × days left ÷ days in the period, in Dhaka days, rounded up to whole taka, plus VAT; the plan switches when that UPGRADE invoice is paid and the period end stays. A downgrade waiting for the renewal (10b) takes effect when the period ends: the GRACE that follows is on the cheaper plan, so its renewal is for that plan. Checkout takes `payBy: BANK_TRANSFER`: an OPEN invoice with the transfer instructions (`mojud.billing.bank-transfer.instructions`) and no gateway; a platform admin marks it paid (`POST /api/v1/admin/billing/invoices/{id}/mark-paid {reference}`), which records a MANUAL attempt and goes through the same `markPaid` as a validated gateway payment: subscription change, invoice PAID, a `billing_ledger` PAYMENT row (append-only, one per invoice), audit, owner notification and `InvoicePaid` (the receipt event; email comes with 13a). `GET /billing/invoices`, `GET /billing/invoices/{id}/print?lang=` (Mojud's invoice as HTML, Bangla or English, VAT shown; seller details in `mojud.billing.seller.*`, placeholders). Admin: `GET /api/v1/admin/billing/invoices?status=` and `GET /api/v1/admin/billing/ledger.csv?month=` (every business, for the accountant). Taka formatting (lakh grouping, Bangla digits) moved to `shared.money.Taka` for slips and invoices. Refunds and voiding invoices are not built yet.

---

## Phase 12: Platform admin

### 12a: Business management (1 day)

**Builds**
- Platform admin flag; admin endpoints use `mojud_system` and are audited.
- `GET /admin/businesses?q=`, business detail (owner, plan, status, usage, invoices), suspend/unsuspend, extend trial, change plan, mark invoice paid, edit plans and limits.

**Checked by:** non-admins get 403 on every admin endpoint; every admin action has an audit row.

**As built:** the platform admin flag (`users.is_platform_admin`, read at sign-in) existed; `/api/v1/admin/**` needs it. Each module answers for its own data and `admin` composes: `tenancy.BusinessDirectory` (search by name part or owner phone/email, detail with owner and members; system role) and `billing.BillingAdministration` (subscription summaries; a business's overview with usage and invoices, read as that business; suspend, unsuspend, extend trial, set plan, edit plans). Suspension sets the subscription SUSPENDED (billing only, 10b) and remembers the status it interrupted (`suspended_from`), restored on unsuspend; tenancy's own business status is not used for it, since that would lock the owner out of billing too. Extending a trial works on the trial plan (trialing, or read-only after it), up to 90 days at a time; setting a plan gives it until a date without payment. Plan edits go through the system role (granted UPDATE on plan columns) and refresh the cached plans; the trial is never offered. Every admin action, including marking a bank transfer paid, writes an `admin_audit` row (admin, action, target, business, details, IP) right after it succeeded; the log is the system role's own (separate connection), readable at `GET /admin/audit?businessId=`. The 403 check walks every registered `/api/v1/admin/**` route, so new admin endpoints are covered automatically.

### 12b: Metrics and data rights (1 day)

**Builds**
- `GET /admin/metrics`: sign-ups, trials, paid businesses, conversion, MRR, weekly active businesses (product plan goals).
- Owner data export (`GET /business/export`, zip of CSVs) and business deletion with a 7-day undo; customer deletion.

**Checked by:** export contains only the owner's business; a deleted business is gone from every table after the undo window (test clock).

**As built:** `GET /admin/metrics` composes counts each module publishes (`identity.UserMetrics`, `tenancy.BusinessDirectory`, `billing.BillingAdministration.metrics`, `audit.ActivityMetrics`): users and sign-ups (7 and 30 days), businesses, trialing, paying (ACTIVE or GRACE on a paid plan), read-only, suspended, ever paid, conversion (paying ÷ businesses whose trial has ended), MRR (paying businesses' monthly prices) and weekly active businesses (a member changed something, per the audit log). `GET /business/export` (owner, in reporting): a zip of orders.csv, products.csv and customers.csv written by the owning modules (`OrderData`, `CatalogData`, `CustomerData`), with no plan limit, open to read-only businesses. Business deletion is `POST /business/deletion {password}` (owner) and `DELETE /business/deletion` to cancel (paths under /business/deletion so a read-only business can use them); the daily job (03:30) calls `mojud_delete_business` (V31): a SECURITY DEFINER function owned by the schema owner (append-only tables refuse DELETE to the app and system roles), run as that business (forced RLS), deleting from every table with a business_id, children first, then the business; the owner's account stays, the trial claim and admin log entries stay detached (`ON DELETE SET NULL`). The 90-day read-only deletion (10b) uses it when enabled (still off). Uploaded files on disk are not removed yet (with the S3 adapter, 13b). Invoices and the billing ledger are deleted with the business, as planned; **check with the accountant whether they must be kept**. Customer deletion (`DELETE /customers/{id}`, managers and owners): refused while an order is in progress; finished orders keep their sale but get "Deleted customer", a placeholder phone and no address (the district stays), and lose the link (`ON DELETE SET NULL (customer_id)`).

---

## Phase 13: Launch readiness

### 13a: Email and two-factor (1 day)

**Builds**
- SMTP `EmailSender` (provider picked then; Brevo or Amazon SES are both cheap), templates in Bangla and English for OTP, invites, reminders, receipts, low-stock digest.
- Daily low-stock digest job.
- TOTP two-factor: optional for owners, required for platform admins.

**Checked by:** template snapshot tests in both languages; login with 2FA on and off.

### 13b: Deployment and backups (1 day)

**Builds**
- `prod` profile; Jib image built and pushed by CI; `deploy/compose.yml` (app, PostgreSQL, Caddy with HTTPS); env file template.
- Nightly encrypted `pg_dump` to R2; restore script; monthly restore-drill workflow in CI.
- Sentry, uptime check on `/healthz`.

**Checked by:** restore drill passes in CI; staging server runs the image (needs a VPS from you at this point).

### 13c: Security review and load test (1 day)

**Builds**
- Pass over the §11 checklist, with fixes.
- Load test: 50 concurrent staff creating and confirming orders on shared variants for 10 minutes; no oversell, ledger matches, p95 latency recorded.
- `docs/operations.md` (deploy, backup, restore, rotate keys).

**Checked by:** checklist ticked in the commit note; load test report in `docs/`.

---

## After Phase 13

1. **Frontend plan**: a separate breakdown like this one, written when you're ready to start the frontend.
2. **Release 2 backend** (detail planned later): R1 channels and Meta webhooks · R2 inbox and SSE · R3 AI engine and evals · R4 AI drafts and auto-confirm · R5 WhatsApp · R6 courier APIs and bKash auto-renewal.

## Waiting on you (not blocking until the phase shown)

| Item | Needed by |
| --- | --- |
| SMS gateway account and sender ID | Before pilot (OTP goes by email until then) |
| SSLCommerz sandbox credentials | 11a manual check (the code is tested against a fake server without them) |
| Email provider choice | 13a |
| VPS (Singapore) and domain | 13b |
| Accountant's answer on VAT | Before the first real invoice |
