# Mojud frontend: implementation plan

Oct 29, 2026 · the web app sellers use, on phones first. It builds on the finished backend (`docs/implementation-plan.md`, Phases 0–13) and the UI mockup ([Mojud UI Preview](https://claude.ai/artifact/U7XobRXtmHmyFshfLGzbSW)). Like the backend, it is split into sub-phases of one commit each, each with what it builds and how it is checked.

## Summary

- **What:** one web app in `frontend/`. Sellers use it on their phones above all, in Bangla first, with English one tap away. It also works well on a laptop.
- **How it's built:** React and TypeScript. It talks only to the existing API, through a client generated from the API's own description, so the two cannot drift apart.
- **Where it runs:** on the same domain as the API, so the cookie sign-in, CSRF protection and "no other origins" rules all work as built.
- **Size:** 12 phases (F0 to F11), about 36 working days full-time. Part-time, about four months, as the backend took. That points the pilot to around **March 2027**.

## Decisions

The product plan already chose React, TypeScript, Vite and TanStack Query, mobile-first, with no native apps. The rest, with the reason for each:

| Area | Choice | Why |
|---|---|---|
| Build | Vite 8, React 19, TypeScript 5.9 (strict), npm, Node 22 | The product plan's stack. npm comes with Node, so nothing else to install (the system's corepack is too old for current pnpm). TypeScript 7 waits until the API type generator supports it. |
| Components | **shadcn/ui** (Radix underneath) with **Tailwind CSS 4**; forms with react-hook-form and zod, tables with TanStack Table, toasts with sonner, icons from Lucide | Chosen on Oct 30, 2026 over Mantine: the components' source lives in the project (`src/components/ui`), fully styled to the mockup, and Tailwind is a widely known skill. The cost, about a week over all phases, is assembling the pieces Mantine would have included. Buttons are 44 px tall by default. |
| Routing | **TanStack Router** | Type-checked routes, and list filters kept in the URL (shareable, survive a refresh), so `/orders?status=CONFIRMED` is typed end to end. |
| Server data | TanStack Query | Caching, retries, and refetching when the seller comes back to the tab. |
| API client | `openapi-typescript` + `openapi-fetch`, generated from the backend's OpenAPI document | Every request and response is typed from the server's own description. A backend test keeps a copy of that description in `frontend/` and fails when it changes (F0), so the client is regenerated with every API change. |
| Languages | i18next, `bn` (default) and `en`, one JSON file each | Numbers, Taka and dates through `Intl` with `bn-BD`/`en-IN`: Bangla digits and lakh grouping (১২,৩৪,৫৬৭) without our own code. Error messages come from the server already translated (it reads `Accept-Language`). |
| Look | The mockup's design: green `#0B6B4F`, ink `#17201B`, ground `#F6F7F5`, Hind Siliguri (self-hosted, Bangla and Latin subsets) | Already reviewed with you, and readable Bangla at small sizes. Self-hosting avoids a call to Google on every visit. |
| On phones | Bottom tabs (Today, Orders, Products, More), a floating "+" for the main action, and a business switcher in the header, as in the mockup | One-handed use. On a laptop the same tabs become a sidebar. |
| Installable | A PWA: home-screen icon, opens full screen, app shell cached | It feels like an app without an app store. Data always comes live from the server: stock must never be shown from an old copy. |
| Printing | The backend's own HTML pages (packing slips, invoices), opened in a new tab | Already built and tested, with correct Bangla shaping (Phase 8d). |
| Charts | shadcn/ui's chart (Recharts) | Styled by the same tokens. |
| Two-step QR code | `qrcode`, drawn in the browser | The secret is never sent to anyone else to draw. |
| Errors | `@sentry/react`, only when a DSN is set at build time | The same Sentry as the backend. |
| Checks | Biome (lint and format), Vitest and Testing Library (units), **Playwright** (end to end, against the real backend) | End-to-end tests run the app against the backend with its throwaway database, reading sign-up codes from the test-only `/dev/otp`. Nothing is mocked where the API decides. |

**Performance budget.** The target is a ৳12,000 Android phone on a busy 4G connection:
- the first screen loads at most 250 KB of JavaScript (compressed) and is usable within 3 seconds;
- each screen loads its own code when opened;
- images use the backend's small size in lists.

**Accessibility.** As the mockup does:
- touch targets at least 44 px;
- every field has a label;
- text at least 4.5:1 contrast;
- usable with the keyboard on a laptop.

Playwright runs an accessibility scan (axe) on every screen.

## How the app talks to the API

- **Same origin:**
  - **Locally:** Vite's dev server sends `/api` to the backend on port 8080.
  - **On the server:** Caddy serves the built files and sends `/api` to the app.
  - So the session cookie and CSRF work unchanged.
- **CSRF:** call `GET /auth/csrf` once at start. Every write sends the `XSRF-TOKEN` cookie back as `X-XSRF-TOKEN`.
- **Errors:** the backend answers with problem JSON carrying a `code` (`stock.insufficient`) and a translated `detail`.
  - **Most errors:** the app shows `detail`.
  - **Some codes are handled specially:**
    - `auth.required` → sign-in;
    - `business.not_selected` → business picker;
    - `plan.limit_reached` (402, with `metric` and `limit`) → upgrade dialog;
    - `subscription.read_only` / `subscription.suspended` → banner, with writes disabled;
    - `rate_limited` → wait and say for how long (`Retry-After`);
    - field errors → under the field.
- **Idempotency:** creating an order, recording a payment and stock changes send an `Idempotency-Key`, so a double tap or a retry on a bad connection never makes two.
- **Types from the server:** the generated types say exactly what arrives.
  - Schemas are named after the class that holds them: `ProductCreateRequest`, `OrderSummary`.
  - Every field of a response is present; a field that can be `null` is marked `@Nullable` (org.jspecify) in the backend and typed `| null`. Each phase marks the fields its screens read.
  - A field left out when empty (cost prices for staff) is optional.
- **Roles:** the UI hides what the member's role cannot do (from `GET /business`). The server still decides; staff never receive cost prices at all.
- **Freshness:**
  - **Notifications:** polled every 60 seconds while the tab is visible.
  - **Lists:** refresh when the seller returns to the app.
  - **Live updates:** server-sent events come with the inbox in release 2.

## Screens and the API behind them

| Area | Screens | API |
|---|---|---|
| Sign-in | Sign up (code by email; phone when SMS arrives), sign in with password or code, two-step code, forgot password | `/auth/*` |
| Account | Profile and language, password, phone and email, two-step sign-in (QR, recovery codes) | `/me`, `/me/two-factor/*` |
| Business | First business (onboarding), switcher, profile and logo, settings, delete (7-day undo), audit log | `/businesses`, `/business*`, `/me/switch-business` |
| Staff | Members and roles, invites, the invite link page `/invites#token`, hand over ownership | `/staff*`, `/public/invites/lookup`, `/invites/accept` |
| Products | List (search, category, tag, low stock), editor (options → variants grid, prices, cost, SKU, images), categories and tags, archive and restore | `/products*`, `/variants/*`, `/categories*`, `/tags*`, `/uploads`, `/files/*` |
| Stock | Receive, damage, count; history per variant; low-stock list | `/stock/*`, `/variants/{id}/movements` |
| Import | Upload, map columns, preview with problems, commit, progress, error file, template | `/imports*` |
| Customers | Search by phone or name, details with order history, edit, delete | `/customers*` |
| Delivery | Division, district and area pickers (Bangla and English names); delivery zones and charges | `/geo/*`, `/delivery-zones*` |
| Orders | Quick order (the main flow); list by status; details with timeline; confirm, pack, ship, deliver, returns, payments, cancel; bulk pack and ship; slips; COD to settle per courier | `/orders*`, `/shipments*` |
| Today and reports | Dashboard; sales and profit by day, product and category; CSV exports; the data export | `/dashboard/today`, `/reports/*`, `/exports/*`, `/business/export` |
| Billing | Plan and usage, choose a plan, pay (SSLCommerz or bank transfer), the return page, invoices | `/billing*`, `/plans` |
| Notifications | Bell with unread count, list, email choices | `/notifications*` |
| Platform admin | Businesses (search, details, suspend, trial, plan), plans, invoices to mark paid, metrics, audit | `/admin/*` (two-step required) |

Every route that the backend's emails link to exists:
- `/billing`, `/billing/invoices/{id}`, `/billing/return`;
- `/products/low-stock`;
- `/settings/notifications`, `/settings/security`;
- `/invites#token`.

## Phases

Sizes are full-time working days. Each sub-phase is one commit with a green CI, as for the backend.

| Phase | Sub-phases | Status |
|---|---|---|
| F0 Skeleton, design, API client, CI, deploy | F0a skeleton, shell, API client · F0b end-to-end tests · F0c web image and deploy | Done |
| F1 Signing in and the account | F1a sign-up, sign-in, forgot password, pages behind sign-in · F1b account settings, two-step sign-in | Done |
| F2 Business, team and settings | F2a first business, switcher, details, logo, settings · F2b staff, invites, leaving, audit log, deleting | Done |
| F3 Products and stock | One commit | Done |
| F4 Importing products | One commit | Done |
| F5 Customers and delivery | One commit | Done |
| F6 Orders, the core | One commit | Done |
| F7 Today, reports and exports | One commit | Done |
| F8–F11 | | Not started |

### F0: Skeleton, design, API client, CI, deploy (3 days)

**Builds**
- `frontend/` with Vite, React, TypeScript, shadcn/ui and Tailwind themed to the mockup, self-hosted Hind Siliguri, i18next (`bn`/`en`), TanStack Router and Query.
- **App shell:** bottom tabs on phones, a sidebar on laptops, the header with the business switcher and the language toggle.
- **API layer:** the generated client, CSRF, the error handling above, `Accept-Language`, idempotency keys.
- **Backend:**
  - `OpenApiSnapshotTests` writes the API description to `frontend/src/api/openapi.json` and fails when it changes;
  - schema names cleaned up where the generated types read badly.
- **CI:** a `frontend` job (lint, types, unit tests, build, bundle-size check) and an `e2e` job (backend with its throwaway database + built frontend + Playwright, phone and laptop sizes).
- **Deploy:**
  - CI publishes the built web app as an image (`mojud-web`: Caddy plus the files);
  - `deploy/compose.yml` uses it instead of the placeholder;
  - the deploy drill checks the app loads over HTTPS.

**Checked by:** the shell renders in both languages at 390 px and 1280 px; a type error in an API call fails the build; the deploy drill loads the app.

**As built (F0a):**
- **Running it:** `npm run dev` in `frontend/` serves http://localhost:5173 and passes `/api` and `/dev` to the backend on port 8080 (`MOJUD_API` changes that).
- **Layout:**
  - Below 768 px: header (brand, language), four bottom tabs, and a "+" to `/orders/new`.
  - From 768 px: a 232 px sidebar with the same tabs.
  - The brand stands where the business switcher goes; the switcher needs signed-in data (F2).
  - Every tab is a "coming soon" page for now.
- **Theme:** the mockup's colours are shadcn/ui's tokens in `src/styles.css`, light only.
- **Language:**
  - Bangla until changed, remembered per device (`mojud.language`); `<html lang>` follows it.
  - Texts in `src/i18n/{bn,en}.json`, keys type-checked, and a test keeps both files' keys equal.
  - `src/lib/format.ts`: taka from poisha, numbers and dates (Dhaka time) through Intl.
- **API:**
  - `api` (openapi-fetch) with `call()`, which returns the body or throws `ApiError` or `NetworkError`.
  - `errorAction()` maps codes to what the app does.
  - The query client shows network, rate-limit and other message errors as toasts; signing in, choosing a business, the upgrade dialog and the read-only banner arrive with their screens (F1, F2, F8).
  - `newIdempotencyKey()` for writes that must not happen twice.
- **Backend:**
  - `ApiTypeNames` names nested records after their class, and the build fails if two would share a name. Before this, 14 request records collapsed into 4 schemas and many responses shared names.
  - `ResponseFields` marks response fields required and `@Nullable` ones nullable.
  - Test-only endpoints (`/dev/otp`, the fake SSLCommerz) are hidden from the description.
  - JSON is the documented answer type.
- **Size:** the first screen is 138 KB of compressed JavaScript; CI fails above 250 KB. The four Hind Siliguri weights load only when shown.
- **CI:**
  - The `frontend` job runs lint, types, unit tests, build and size, and checks that `schema.d.ts` and the route tree match what's committed.
  - The Dependencies workflow also scans `package-lock.json`, and Dependabot proposes npm updates.

**As built (F0b):**
- **How it runs:** `npm run e2e` runs Playwright (`frontend/playwright.config.ts`).
  - It starts the test backend (`spring-boot:test-run`) and the production build (`vite preview`, with the same `/api` and `/dev` proxy).
  - Locally it reuses servers already running on 8080 and 4173; the backend must be `TestMojudApplication`.
  - Chromium runs at two sizes: phone (390×844, touch) and laptop (1280×800).
- **Helpers in `e2e/support`:**
  - `signUp(request)` creates an account through the app's origin, reading the code from `/dev/otp`; each call uses a new phone number, so tests never collide.
  - `expectAccessible(page)` runs axe for WCAG 2.2 A and AA.
- **Tests:** the shell in both languages, the tabs, the language remembered across a reload, "+" only on phones, the not-found page, no sideways scrolling, and the API through the app's origin (sign-up, session cookie, answers in the requested language).
- **Found by the accessibility scan:** the language button's spoken name differed from its visible text (WCAG 2.5.3). The visible "English"/"বাংলা" is now its name.
- **CI:** an `e2e` job runs it all (about 20 s of tests after the build), and on failure keeps the report with screenshots and traces for 7 days.

**As built (F0c):**
- **The image:** `frontend/Dockerfile` builds the app on the builder's platform and copies `dist/` into `caddy:2` at `/srv/web`.
  - On `main`, CI's `web-image` job pushes `ghcr.io/al-mahamud/mojud-web` (amd64 and arm64, tagged with the commit and `latest`) once `frontend` and `e2e` pass.
  - The backend image now also waits for `e2e`.
- **On the server:** `deploy/compose.yml` runs it as `caddy` (`WEB_IMAGE`), replacing the placeholder page.
- **Caddy for the web app** (the API keeps its own headers):
  - Security headers: a Content-Security-Policy allowing only this server (inline styles allowed), HSTS, nosniff, `Referrer-Policy: same-origin`, and the same Permissions-Policy as the API.
  - `/assets/*` cached for a year, a missing file a 404; everything else `no-cache`, with the app's routes answered by `index.html`.
- **The deploy drill:**
  - Checks those headers, a deep link, an asset's caching and a missing asset's 404.
  - Runs `e2e/shell.spec.ts` in Chromium against `https://localhost` (`E2E_BASE_URL`), so a policy that blocks the app shows up as a failure.
  - The shell tests fail on any console error.
- **Dependabot:** watches the image's bases.
- **Pending with 13b:** the real server, and the domain (the plan's open decision 5).

### F1: Signing in and the account (3 days)

**Builds**
- **Sign-up:** name, phone, email, password, then the 6-digit code. Only the code channels the server offers (`/auth/options`) are shown.
- **Sign-in:** with a password or a code; the two-step screen (app code or recovery code); forgot password.
- **Account settings:**
  - profile and language (saved to the account);
  - password, and phone and email changes with codes;
  - two-step sign-in: QR code, the 10 recovery codes shown once to save or print, turning it off.

**Checked by:** e2e: sign up with an email code, sign out, sign in by password, by code, and with two-step on (codes computed in the test as the backend tests do); wrong codes show the server's message in both languages.

**As built (F1a):**
- **Routes:**
  - `_app` holds everything behind sign-in. Signed out, it goes to `/sign-in?redirect=<page>`, and back after signing in; `safeRedirect` allows only paths on this site.
  - `_auth` holds `/sign-in`, `/sign-up` and `/forgot-password`, for people not signed in.
- **Session:**
  - `meQuery` is the account, or null when signed out, and the guards read it from the cache.
  - When any answer says `auth.required`, `onSignedOut` clears everything in memory and goes to sign-in, keeping the page.
  - A failed load shows `RouteError` with a retry.
- **Language:** the account's language wins once signed in, because the server answers in it; the language button also saves it (`PATCH /me`).
- **Forms:**
  - react-hook-form with zod, in `src/components/form`: `TextField`, `PasswordField` (show/hide), `CodeField`, `FormError`.
  - `showServerError` puts the server's problems under their fields or above the form.
  - Our messages are text keys, so an error on screen follows the language.
  - Bangla digits in phone numbers and codes become Latin before sending.
- **Sign-up:**
  - Details, then the code; the choice of phone or email appears only when the server offers both, and with none the page says sign-up is unavailable.
  - A refused detail leads back to the details, with the problem under its field.
- **Sign-in:**
  - By password or code; the two-step screen (`auth.two_factor_expired` starts over) is built here and checked in F1b, when an account can turn it on.
  - Resending a code waits out the server's minute.
- **Sign-out:** on More for now.
- **Backend:** the API description now includes `POST /auth/logout` (Spring Security's, added by hand) and both answers of `POST /auth/login`.
- **Size check:** now counts what a first visit really loads, every page's shared code plus the heaviest page: 189 KB of the 250 KB budget.
- **E2E:**
  - `public.spec.ts` needs no test backend, so the deploy drill runs it; `auth.spec.ts` and `shell.spec.ts` need `/dev/otp`.
  - Every test fails on a console error, apart from refused API calls, which are normal.

**As built (F1b):**
- **Where:** More links to `/settings/account` and `/settings/security` (the address the security emails link to).
- **Account:**
  - Name, and language with two buttons.
  - Phone and email show verified or not, and a button to add, verify or change, offered only when the server can send codes that way.
  - The dialog sends a code, then takes the code and the current password. A number or address someone else has leads back to its field.
- **Security:**
  - Changing the password, with the note that it signs out other devices.
  - Two-step sign-in: the password, then the QR code (drawn in the browser, loaded only when needed) with the key to type instead, then the app's first code.
  - The 10 recovery codes are shown once, in a dialog that closes only with "I've saved them". They can be copied or saved to a file, and printed from there.
  - New recovery codes, and turning it off (password plus an app or recovery code).
- **The "+" button:** hidden on More, the settings pages and the new-order page.
- **E2E:**
  - `e2e/support/totp.ts` is an authenticator app written apart from the server's code; wrong codes and passwords are refused under their fields.
  - The full journey: turn on, sign in with the app's code, sign in with a recovery code, the count drops to 9, turn off, sign in with only the password.
  - 60 tests across both screen sizes; a double run with no retries passed all 120.
- **Size:** 204 KB for the heaviest first visit (the security page), within the 250 KB budget.

### F2: Business, team and settings (3 days)

**Builds**
- **First-run:** create the first business.
- **Switching:** the switcher with role badges.
- **Business profile:** name, phone, address, logo.
- **Settings:** stock commit point, low-stock default, order number prefix, invoice footer.
- **Staff:** list, change role, remove, invite by phone or email, cancel an invite, hand over ownership with the password.
- **The invite link page:** preview, then sign in or sign up with the invited contact, then join.
- **Audit log** (owner).
- **Deleting the business:** password, 7-day countdown banner, undo.

**Checked by:** e2e: an owner invites a manager, who joins from the link on a second browser; the manager cannot see owner-only pages; staff removal signs the staff member out of that business.

**As built (F2a):**
- **The guard:** `_app` also needs a selected business. With none it goes to `/welcome`, a form with the business's name (phone and address optional), and sign-out for those who came to join someone else's.
- **Which business:** `businessQuery` (`GET /business`) is the selected one, and it carries the role.
  - The server moves the selection by itself when a member is removed: the next request silently runs in their other business, or none. So the app reads it again every minute and on returning to the app, and an answer naming another business means a warning, all cached data dropped, and a new start at Today.
  - A `business.not_selected` or 403 from any request triggers the same check.
  - Within that minute, writes are protected too (added in F3): each names the business on screen in `X-Business-Id`, and the server refuses it with `business.changed` if the session has moved.
- **Roles:** `hasRole` hides what a role cannot do and `requireRole` keeps pages out of reach, with the server still deciding.
- **Switcher:** in the header on phones and the sidebar on laptops: logo or initials, name, role. The list loads when opened; it can create another business. Switching drops everything cached from the other one.
- **`/settings/business`** (managers and owners): details; logo upload, change and remove (the server's message for an image too small or too large); when stock goes down, low-stock level, order number start (upper-cased as typed), slip footer.
- **Accessibility scan caught:** the switcher's spoken name (now hidden text plus the visible name), and a menu hiding the page from screen readers while open (`modal={false}`).
- **A bug the tests caught:** several requests failing together when a session ended each added a "return to" onto the sign-in link. Only the first counts now.

**As built (F2b):**
- **Backend:** `POST /staff/leave` for any member but the owner, who hands over first; the leaver's open invites go too, and it's in the audit log (`staff.left`). A test-only `GET /dev/invite?to=` reads invite tokens, as `/dev/otp` reads codes.
- **`/settings/staff`** (managers and owners):
  - Invite by phone or email with a role, with a plain explanation of the roles; open invites can be cancelled.
  - Members: change role, remove with a confirmation; owners can also hand over with the password. Nobody edits themselves or the owner, as the server's rules say.
- **`/invites#token`:**
  - Works signed in or out: which business and role, who invited, then joining.
  - Signed out, it leads to sign-up or sign-in and back. The token waits in `sessionStorage`, never in the sign-in page's `redirect`, because URLs reach Caddy's logs and fragments don't.
  - A dead link, or an invite for another account, shows the server's message.
- **Leaving:** a button on More, except for owners. It leads to their next business, or `/welcome`.
- **`/settings/audit-log`** (owners): newest first, with "show more". Every action the backend records has a text in both languages; one added later shows by its code until translated.
- **Deleting:** on `/settings/business` (owners), with the password. A banner then shows the date on every page, for every member, and the owner can undo it there.
- **E2E (`team.spec.ts`):**
  - Two browsers: the owner invites a manager, who signs up from the link and joins; managers and staff can't reach pages beyond their role.
  - A removed member's app notices within a minute (with a fake clock) and leaves the business.
  - Role changes, handing over, leaving, cancelled and wrong-account invites, deleting and undoing.
  - 80 tests across both screen sizes; a double run with no retries passed all 160.
- **Accessibility scan caught:** the green button's hover colour was too pale for white text; it now goes darker, as in the mockup.
- **Size check corrected:** a page under the tabs also needs the tab frame's code, which it wasn't counting.
  - Moving the form libraries out of the frame (the new-business dialog loads when opened) took Today from 232 KB to 188 KB.
  - Form pages are the heaviest at 231 KB of the 250 KB budget, so F3's product editor will need watching.

### F3: Products and stock (5 days)

**Builds**
- **Product list:** as in the mockup; search, category and tag chips, low-stock badge, infinite scroll.
- **Product editor:**
  - details, category and tags;
  - options (Size: S, M, L), with the variants grid built from them;
  - per-variant price, cost (hidden for staff), SKU, low-stock level, archive and restore;
  - images: upload, reorder, main image.
- **Categories (two levels) and tags.**
- **Stock:** receive, damage and count with a reason; a variant's movement history; the low-stock list.
- **Plan limits:** the upgrade dialog when the variant limit is reached.

**Checked by:** e2e: create a product with 2 options and 6 variants on a phone screen; receive stock; a staff member sees no cost anywhere; the 402 limit dialog appears at the trial limit (lowered in the test).

**As built (F3):**
- **The wrong-business guard:** `RoleInterceptor` compares `X-Business-Id` with the session's business on business-scoped writes (409 `business.changed`, nothing done). The client sends whichever business is in its cache.
- **`/products`:**
  - Cards on phones, a table from 768 px, as in the mockup; pages load as the end comes into view.
  - Search, one category or tag at a time as chips (subcategories appear under their category), active or archived; all kept in the address.
  - A low-stock badge per product: `lowStock` added to the backend's list, using the same rule as the low-stock list.
- **`/products/new`** (managers and owners): details, category, tags (new ones on the spot), options with the number of variants they make, price, cost, SKU, opening stock.
- **`/products/$id`:**
  - Everyone sees it and changes stock. Managers and owners edit details, images, options and variants, and archive and restore.
  - Stale edits are refused by `version`; the form takes the latest and shows the server's message.
  - Each variant is edited in a dialog, not inline as the mockup's grid shows: a table of inputs doesn't work on a phone. Seller sessions (F11) will show whether laptops want the grid too.
- **Images:** up to 8, several at once. Opening one offers making it main, moving it, which variant it shows for, and removing it; buttons, not dragging, so it works by touch and keyboard.
- **Stock:**
  - One dialog for any of the product's variants at once: arrived, damaged or lost, or counted, with a reason. All of it happens or none; the idempotency key is kept across retries of one change.
  - History per variant, in plain words.
  - `/products/low-stock`, where the low-stock email links.
- **`/settings/categories`** (managers and owners): two levels, add, rename, move, reorder, delete; tags.
- **Plan limits:** any 402 `plan.limit_reached` opens one dialog, with what the limit is and a link to `/billing`, a placeholder until F8.
- **Staff and cost:** the test reads the server's raw answer as well as the screen: no cost or margin anywhere.
- **Language files load separately**, only the one in use, so growing texts don't weigh on every page.
- **Size:** every page loads 160 KB; the list adds 26 KB (186 KB); the heaviest page, a product with its forms, is 243 KB of 250. F6's order forms must share these form libraries, not add to them.
- **E2E (`products.spec.ts`):** 100 tests across both screen sizes; a double run with no retries passed all 200. The limit test puts a business on the real Starter plan with a test-only `POST /dev/plan`, and fills it with 500 variants.

### F4: Importing products (2 days)

**Builds**
- **Uploading:** the template download, then upload (CSV or Excel).
- **Checking:** column mapping with the server's guesses, and a preview with problems per row.
- **Importing:** commit, then progress polling, the result, and the error CSV.

**Checked by:** e2e: import the template with 20 products, one with a bad price; the problem is shown; after fixing, all 20 appear.

**As built (F4):**
- **`/products/import`** (managers and owners), reached from the product list. The import in progress stays in the address (`?job=`), so a refresh comes back to it.
- **Upload:** the template and the file (`.xlsx` or `.csv`). The server's messages explain unreadable or empty files.
- **Check:** totals, new categories, and rows with their problems (problems first), with the button to start at the top, since most sellers won't need to touch the columns. Below it, the column list with the server's guesses: each change is sent at once and every row is checked again.
- **Importing:** progress every 1.5 s, which carries on if the seller leaves. Then the result, with the rows with problems as a file to fix and upload again.
- **Backend bugs found by writing this:** sellers' own sheets rarely use our exact headers, and that path was broken.
  - A sheet with no recognised name column crashed the upload (500).
  - One with no recognised price column counted its rows as fine, and they couldn't be imported.
  - A file with nothing under its header also crashed.
  - Now: the job says `missingColumns`, no row counts as valid and the import can't start until they're mapped, a partial mapping is accepted so columns can be set one at a time, and a file with no rows below its header is `import.empty`.
- **E2E (`import.spec.ts`):** the plan's check with 20 products and one bad price, fixed in the downloaded file; unrecognised columns mapped by hand; the template, an unreadable file, and a refresh. 106 tests across both screen sizes; a double run with no retries passed all 212.

### F5: Customers and delivery (2 days)

**Builds**
- **Customers:** list and search (by phone, as sellers do), details with order history and totals, edit, delete with the server's open-orders rule.
- **Place picker:** division → district → area, searchable in Bangla and English.
- **Delivery zones:** zones, their districts and areas, and charges.

**Checked by:** e2e: find a customer by any common phone format; a zone's charge is what a new order is quoted.

**As built (F5):**
- **`/customers`** (everyone, from More):
  - Search: a whole phone number, however it's written, goes to the server as a phone number; anything else searches names and parts of numbers.
  - New and edit in one dialog, with the address and place.
- **`/customers/$id`:** tap to call, address, the team's note, and the order history with its totals per order.
  - Delete for managers and owners, refused with the server's message while orders are in progress.
  - Not built: a lifetime spend figure. The backend gives the order count only, and the customers module can't read orders; add a small endpoint if sellers ask.
- **`PlacePicker`** (`src/features/delivery`), for customers now and orders in F6:
  - District, then thana or upazila, each a list with a search box that takes either language.
  - The plan said division → district → area. Districts are listed under their divisions instead of a separate step, which is quicker on a phone.
- **`/settings/delivery`** (managers and owners): all the zones are edited together and saved as one, since a place can move between them: name, charge, the "everywhere else" zone, districts and areas. A check at the bottom shows the server's own quote for any place, the one the order form will use.
- **Fixed along the way:**
  - The router read an all-digit search in a link as a number, which broke the page (`searchText`).
  - Numbers in labels such as "অপশন ১" now use Bangla digits.
  - A new business's two zones are named in its creator's language ("ঢাকার ভেতরে", "ঢাকার বাইরে").
- **E2E (`customers.spec.ts`):** finding a customer by five ways of writing a number, picking places by searching in English, a taken number refused under its box, the order history, deleting refused and then allowed, and a new zone's charge from the server's quote. 112 tests across both screen sizes.

### F6: Orders, the core (6 days)

**Builds**
- **Quick order:** on a phone, from a chat, in under a minute:
  1. paste or type the phone number, which finds the customer or adds a new one in place;
  2. the place picker;
  3. add items from a search that shows what is available now;
  4. the delivery charge, quoted and editable;
  5. discount, advance and the COD amount worked out;
  6. note; then save as a draft or confirm.
- **Orders list:** tabs by status with counts, filters by date, phone and number.
- **Order details:**
  - items, money and the timeline;
  - the actions the status allows: confirm, pack, ship (courier, picked from those used before, and tracking number), deliver (COD collected, refused items), returns (restock or write off), payments, cancel with a reason.
- **Bulk:** select orders to pack or ship together.
- **Printing:** packing slips for one or many orders.
- **COD to settle:** a page per courier, and marking it settled.

**Checked by:**
- **The main flow on a phone:** e2e takes a new order for a new customer at 390 px through confirm, pack, ship and deliver with COD, then a return.
- **Out of stock:** two browsers confirm the last unit, and one sees the server's out-of-stock message.
- **Bad connection:** a double-tapped save makes one order.
- **Timed:** the quick order is timed in the e2e test.

**As built (F6):**
- **Quick order (`/orders/new`, also from a customer's page):** one page, top to bottom.
  - The whole phone number finds the customer, with their address and place filled in; otherwise a name box is there, and the customer is made with the order.
  - Items come from a search showing what is available; a product with variants asks which. Quantity and price are editable per line, and more than is available shows a warning without stopping a draft.
  - The charge follows the place until the seller types their own. Discount, advance (with how it was paid) and the COD amount work out as they type.
  - Confirming checks the address and district first, and goes to whatever needs fixing. If the server then refuses (out of stock), the order stays a draft and its page opens with the reason, naming the items.
  - Added beyond the plan: a different recipient, folded away; gifts are common.
- **Editing (`/orders/$id/edit`):** the same form, for drafts and confirmed orders. A draft couldn't otherwise get its missing address; a confirmed order's customer stays.
- **List (`/orders`):** tabs by status with counts, search by order number or phone, dates folded away; every filter is in the address. Tick orders to pack, ship (one courier, a tracking number each) or print together; the answer says which failed and why. A failed order leaves the selection: its status has changed, so trying again wouldn't help.
- **Details (`/orders/$id`):** the status's actions, the customer, items, money, courier and the history, with who did what. Dialogs: ship (couriers used before one tap away), deliver (COD collected; refused items restocked or written off), parcel came back (and ship again), customer return (with refund), payment, cancel.
- **COD (`/cod`, from More and the list):** per courier, what it owes and what is still on the way; all ticked at first, untick what this payout doesn't cover. Managers and owners settle. Not built: a history of past settlements (each order's page shows its own).
- **Printing:** slips open in a new tab, in the language on screen, and open the print dialog.
- **Backend:**
  - `GET /orders/counts`, with the list's filters.
  - The timeline: `events` on an order (from `order_events`, which nothing could read before).
  - `Idempotency-Key` on payments: this plan promised it, and the backend ignored the header.
- **No form libraries here:** these screens use `components/form/Field.tsx` and their own state, because lines and amounts depend on each other. The heaviest of them comes to 214 KB.
- **Order numbers** are shown as the slips print them, in Bangla digits (`#১০০১`). Search takes either digits.
- **E2E (`orders.spec.ts`, 12 tests):** the main flow at both sizes, timed; a known customer and the charge by place; two browsers and the last unit; a double tap; a lost answer retried; refused items; a parcel back and sent again; a payment and a cancel; the list, bulk and printing; COD. 136 tests across both screen sizes.
- **`expectAccessible` looks again for up to 5 seconds:** colours can't be judged halfway through a fade. A lasting problem still fails.
- **Size:** the heaviest first visit is 249.2 KB of 250, a product page with its form libraries. Order pages are 214 KB at most. Every phase's text adds to all pages, about 2.5 KB this time, so **F7 must make room first**: see below.
- **For F11:** phone numbers show as `+8801712345678` on screen; slips already print them the local way.

### F7: Today, reports and exports (3 days)

**Builds**
- **Dashboard:** today's sales, orders by status, pending deliveries, COD outstanding, low stock, a 7-day sales chart, and any day picked.
- **Reports:** sales by day, product or category, and profit (managers and owners); within the plan's date range.
- **Downloads:** CSV exports, and the owner's full data export.

**Checked by:** the figures on screen equal the API's for a seeded day; staff do not see profit.

**As built (F7):**
- **Room in the budget first:** the form pages moved from `zod` to `zod/mini` (5 KB against 22 KB, the same rules). The heaviest first visit went from 249.2 KB to 232 KB, and is 233.6 KB with F7's text. Mini has no default messages, so every rule names its own text key; an SKU that was too long used to get an English message.
- **Today (`/`, `?date=` for another day):** as in the mockup: tiles for sales, to pack, with couriers, COD outstanding and low stock, each leading to that work; orders by status; best sellers; running low.
  - Managers and owners also see profit on goods. Staff get `profit: null` from the server.
  - COD, low stock and open orders are as they are now; the server doesn't keep them per day.
- **Seven-day chart:** plain HTML columns, no chart library. It always ends today, so a Starter plan's 90-day reach can't refuse it. A column is a button that opens that day; the line above says what the hovered or chosen one stands for, and is what a screen reader hears. It adds delivery to goods, so today's column equals the sales tile.
- **Reports (`/reports`, from More and Today):**
  - Sales by day, product or category, over any days, with quick ranges. Days without sales are left out. A note says what the figures count, since product and category figures are before order discounts.
  - Profit (managers and owners): goods, delivery and the total, with a warning when items have no cost price.
  - Everything is in the address, so a report can be bookmarked.
- **Downloads (managers and owners):** orders for the chosen days, products and customers as CSV; owners also get the whole business as a ZIP. They go through the API client rather than a plain link, so a refusal shows the upgrade dialog instead of a page of JSON. The files are held in memory while saving, which is fine at these sizes.
- **Plan limits:** the dialog now says "your plan has no downloads" and "reports for the last 90 days" rather than "the limit is reached", which was wrong for those two.
- **`errorMessages`** (`api/query.ts`): a refused request's reasons, for screens with no field to put them under, such as a range of more than 366 days.
- **Backend:** only `@Nullable` on what these screens read.
- **E2E (`reports.spec.ts`):** the figures against the API for a seeded day, hand-checked too; other days from the chart and by date; staff and profit; all three reports and profit's arithmetic; all four downloads, their contents read; both plan limits. 148 tests across both screen sizes.
- **A test bug found:** the password-reset test could type before the page changed, into the sign-in page's box of the same name. It failed once in about 300 runs. Reproduced by slowing the page down on purpose, then fixed in the test. The app was fine.

### F8: Billing (3 days)

**Builds**
- **Plan and usage:** the current plan, status and usage against limits.
- **Choosing a plan:** plans with prices; months (1, 6, 12) and payment (SSLCommerz or bank transfer).
- **Returning from payment:** the return page polls the invoice until it is paid.
- **Bank transfer:** the instructions.
- **Invoices:** the list, and printing.
- **Changing plan:** upgrade with the prorated amount; downgrade at renewal.
- **Banners:** for the trial ending, grace and read-only (writes disabled, with a reason).

**Checked by:** e2e: pay through the backend's fake gateway and the plan becomes active; a read-only business shows the banner and the server refuses a write the UI did not hide.

### F9: Notifications (1 day)

**Builds**
- **The bell:** unread count, polled; a list with links to the thing it is about; mark read.
- **Email choices:** the low-stock digest on or off.

**Checked by:** a low-stock alert appears within a minute of stock falling.

### F10: Platform admin (2 days)

**Builds**
- **Where:** `/admin`, only for platform admins, with a reminder to turn on two-step sign-in when it is off.
- **Businesses:** search, details (owner, members, subscription, usage, invoices), suspend and unsuspend with a reason, extend a trial, set a plan.
- **Platform:** edit plans, mark bank transfers paid, metrics, the admin audit log.

**Checked by:** e2e: an admin without two-step sees the reminder, not the data; with it, suspending a business shows that business the suspended banner.

### F11: Launch polish (3 days)

**Builds**
- **Installable:** PWA (manifest, icons, app shell), "add to home screen" on Android.
- **Speed:** a performance pass on a throttled mid-range phone profile.
- **Accessibility:** a pass with axe on every screen.
- **Bangla:** every text read through by a native speaker (you).
- **Sentry** for the browser.
- **Sessions with sellers:** 3–5 sellers try the main flows on their own phones; fixes from what they stumble on.

**Checked by:** within the performance budget; no axe violations; the main flows done unaided by most of the sellers.

## Small backend changes the frontend will need

Found while mapping the screens. Each lands in the frontend phase that needs it, with its own tests:
- **API description snapshot test:** keeps `openapi.json` current (F0).
- **Leave a business:** a member can't leave by themself yet; only the owner can remove them (F2).
- **Admin reset of someone's two-step sign-in:** for a lost phone (F10).
- **Small read endpoints wherever a screen would otherwise make many calls:** for example, order counts by status for the tabs (F6).

## Open decisions

Recommendations first. Tell me where you disagree; nothing is blocked until the phase named.

1. **Components:** decided on Oct 30, 2026: shadcn/ui.
2. **Default language:** Bangla, with English one tap away, as built in F0a. Say if English should come first.
3. **Admin area:** inside the same app at `/admin` (recommended; one build, one sign-in), or a separate app. Decide before F10.
4. **Seller sessions:** can you find 3–5 sellers for 30 minutes each, on their own phones? Needed by F11; earlier is better (after F6).
5. **Domain:** the app and API on one address, e.g. `app.<your domain>` (needed by F0's deploy, but a placeholder works until the server exists).
