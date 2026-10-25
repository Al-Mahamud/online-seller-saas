# Online Seller SaaS — Detailed Build Plan

Oct 25, 2026 · @Mahamud

## Summary

Build a multi-tenant SaaS where online sellers run inventory, orders and delivery from one place, and Pro sellers get an AI agent that answers Messenger and WhatsApp customers using live stock data. The plan ships a paid-ready inventory and order product first (about 4 months for one full-time developer), then adds the AI inbox (about 3 more months).

The first market is Bangladesh F-commerce sellers who sell through Facebook pages and WhatsApp, ship by courier and collect cash on delivery. Every estimate assumes one full-time full-stack developer; part-time work roughly doubles the calendar.

How to review: read each section's first sentence for the decision, then check the Open decisions section at the end. Comment anywhere you disagree.

## Goals, metrics and non-goals

The launch goal is 20 paying sellers within 3 months of the MVP going live, with the AI inbox answering at least 60% of routine questions without a human.

| Goal | Metric | Target (proposed) |
| --- | --- | --- |
| Sellers replace spreadsheets | Weekly active businesses | 50 by month 3 after launch |
| Sellers pay | Paid conversions from Free | 20 paying, 10% conversion |
| Stock stays correct | Orders that oversell | Under 0.5% of orders |
| AI saves seller time | Conversations resolved without handoff | 60% or more |
| AI tells the truth | Replies with wrong stock or price (sampled weekly) | Under 1% |
| AI is affordable | AI cost per Pro business per month | Under 25% of Pro price |

Non-goals for the first release:

- A public storefront or website builder.
- Native mobile apps (the web app must work well on phones instead).
- Accounting, payroll or tax filing.
- Instagram, Telegram or other channels beyond Messenger and WhatsApp.
- Multi-warehouse inventory.
- AI that creates orders or changes stock without seller-defined rules.

## Target users and core workflows

The primary user is a small Facebook-page seller with 1 to 5 staff, 50 to 2,000 products and 10 to 200 orders a day, mostly cash on delivery.

| Persona | Who they are | Main pain today | What they need first |
| --- | --- | --- | --- |
| Solo seller | Owner runs the page, packs and replies alone | Answers the same price and stock questions all day | Fast product lookup, AI replies |
| Small team | Owner plus 2 to 4 staff handling inbox and packing | Stock in a spreadsheet goes out of date; orders lost in chat | Shared orders list, staff roles, stock sync |
| Growing brand | 500+ SKUs with sizes and colors, several couriers | Returns and failed COD deliveries not tracked | Variants, courier status, return tracking |

Core workflows to design and test, in priority order:

1. Add products with variants (size, color) and opening stock, or import them from Excel/CSV.
2. Take an order from a chat: pick customer, add items, set delivery charge and COD amount, confirm.
3. Pack and hand over to a courier; record the courier and tracking number.
4. Update delivery status through to delivered, returned or partially delivered; collect COD.
5. Process a return: restock good items, write off damaged ones.
6. (Pro) AI answers a Messenger or WhatsApp question with live stock and price, collects order details and hands the draft to staff.
7. Owner checks the daily dashboard: sales, pending deliveries, low stock, COD outstanding.

Before building, interview 8 to 10 sellers to confirm these workflows, especially how they record COD, partial deliveries and returns.

## MVP scope by module

The MVP is modules 1 to 3 plus billing; the AI inbox is release 2. Priority: P0 = must ship in its release, P1 = ship if time allows, P2 = later.

| Module | Feature | Release | Priority |
| --- | --- | --- | --- |
| Accounts | Sign up with email or phone + OTP, business profile, logo, currency (BDT) | MVP | P0 |
| Accounts | Staff invites with roles: Owner, Manager, Staff | MVP | P0 |
| Inventory | Products with SKU, cost price, sale price, images, description | MVP | P0 |
| Inventory | Variants (size, color) each with its own SKU, price and stock | MVP | P0 |
| Inventory | Categories and tags | MVP | P0 |
| Inventory | Excel/CSV import with preview, validation and error report | MVP | P0 |
| Inventory | Stock adjustments with reason (received, damaged, count correction) and full history | MVP | P0 |
| Inventory | Low-stock alerts per variant threshold | MVP | P1 |
| Inventory | Barcode/label printing | Later | P2 |
| Orders | Customers with phone, address, district/area, order history | MVP | P0 |
| Orders | Order creation with items, discount, delivery charge, COD amount, notes | MVP | P0 |
| Orders | Order status workflow with stock reservation (see Inventory and order logic) | MVP | P0 |
| Orders | Payment status: unpaid, partial, paid, COD collected | MVP | P0 |
| Orders | Returns and cancellations with restock or write-off | MVP | P0 |
| Orders | Invoice / packing slip PDF | MVP | P1 |
| Orders | Courier booking via API (Steadfast, Pathao, RedX) | Release 2 | P1 |
| Dashboard | Today's sales, orders by status, low stock, COD outstanding | MVP | P0 |
| Dashboard | Sales by product and period, profit from cost price | MVP | P1 |
| SaaS | Plans, limits, subscription status, upgrade/downgrade | MVP | P0 |
| SaaS | Payment through a local gateway, invoices | MVP | P0 |
| Admin | Platform admin: businesses, plans, usage, suspend | MVP | P0 |
| AI inbox | Connect a Facebook page (Messenger) | Release 2 | P0 |
| AI inbox | Unified inbox: conversations, staff replies, assign, mark resolved | Release 2 | P0 |
| AI inbox | AI auto-reply grounded in products, stock, policies and order status | Release 2 | P0 |
| AI inbox | Human handoff, pause AI per conversation, review queue | Release 2 | P0 |
| AI inbox | AI-drafted orders that staff confirm with one click | Release 2 | P0 |
| AI inbox | WhatsApp Business connection | Release 2 | P1 |
| AI inbox | Bangla, English and Banglish (romanized Bangla) replies | Release 2 | P0 |
| AI inbox | Customer delivery-status notifications | Later | P2 |

## Plans, limits and AI metering

Three plans, with every limit stored as data in a plan table so you can change limits and prices without a code release. Prices below are placeholders to test with sellers, not recommendations.

| Limit | Free | Starter | Pro |
| --- | --- | --- | --- |
| Price per month (placeholder) | ৳0 | ৳990 | ৳2,990 |
| Products (counting each variant) | 50 | 500 | 5,000 |
| Orders per month | 100 | 2,000 | Unlimited (fair use) |
| Staff seats | 1 | 3 | 10 |
| Excel/CSV import | Up to 50 rows | Yes | Yes |
| Reports | Today only | 90 days | Full history, export |
| Unified inbox (human replies) | No | Yes, 1 channel | Yes, 2 channels |
| AI replies per month | 0 | Add-on pack | 1,000 included |
| Extra AI replies | — | ৳500 per 500 (placeholder) | ৳500 per 500 (placeholder) |

What counts as one AI reply: one outgoing message the AI sends to a customer. Messages a human sends, AI drafts a human rejects, and AI runs that end in handoff without a reply are not counted. Internally, also record input and output tokens and model cost on every run, so you can see real cost per business and adjust allowances.

What happens at the limit:

- At 80% of the AI allowance: in-app and email warning to the owner.
- At 100%: AI stops auto-replying and new messages go to the human inbox; the owner can buy a pack. Never cut off mid-conversation without a handoff message.
- Product and order limits: block new creation with an upgrade prompt; never hide or delete existing data.
- Failed renewal: 7-day grace period, then read-only mode; data kept for 90 days.

## System architecture and stack

Build one modular monolith in TypeScript, deployed as an API process and a worker process from the same codebase, with PostgreSQL as the single source of truth.

&#91;embedded content: system architecture · 6 modules, 1 worker, 5 external services\]

Modules call each other only through service interfaces and never read another module's tables, so any module (most likely Inbox and AI) can later become its own service.

| Layer | Choice | Why |
| --- | --- | --- |
| Frontend | React + TypeScript + Vite, TanStack Query, a component library such as Mantine or shadcn/ui | Fast to build, mobile-first screens for sellers on phones |
| Backend | NestJS (TypeScript) | One language end to end; Spring Boot is equally valid if you prefer Java |
| ORM and migrations | Prisma or Drizzle, migrations in the repo | Typed queries, repeatable schema changes |
| Database | PostgreSQL with row-level security | Transactions for stock, JSON for flexible attributes |
| Queue and cache | Redis + BullMQ | Webhook processing, AI jobs, retries, rate limits, quota counters |
| File storage | S3-compatible storage (Cloudflare R2, DigitalOcean Spaces) | Product images and import files |
| AI | LLM with tool calling, behind a provider adapter | Swap or mix models as prices change |
| Hosting | Docker Compose on one VPS in Singapore, nightly backups | Low cost and low latency to Bangladesh; move to managed PostgreSQL as you grow |
| Monitoring | Sentry, uptime checks, structured logs | Find errors before sellers report them |
| Real-time | WebSockets (Socket.IO) for the inbox | New messages appear without refresh |

## Data model

Every business-owned table carries `business_id`, and PostgreSQL row-level security enforces it in addition to the application filter. Stock is never stored as a single editable number: it is the sum of an append-only stock movement ledger, cached on the variant for fast reads.

| Area | Table | Key columns | Notes |
| --- | --- | --- | --- |
| Tenancy | businesses | id, name, slug, currency, timezone, plan\_id, status | One row per seller |
| Tenancy | users | id, email, phone, password\_hash | A user can belong to several businesses |
| Tenancy | memberships | business\_id, user\_id, role | Owner, Manager, Staff |
| Catalog | categories | business\_id, name, parent\_id | Optional nesting |
| Catalog | products | business\_id, category\_id, name, description, images, status | Shared details |
| Catalog | product\_variants | product\_id, sku, attributes (size, color), price, cost\_price, on\_hand, reserved, low\_stock\_threshold | on\_hand and reserved are caches of the ledger |
| Stock | stock\_movements | variant\_id, type, qty (+/−), reason, order\_id, user\_id, created\_at | Append-only: receive, sale, reserve, release, return, damage, adjust |
| Stock | import\_jobs | business\_id, file, status, rows\_ok, rows\_failed, error\_report | CSV/Excel import tracking |
| Orders | customers | business\_id, name, phone, address, district, area, channel\_ids | Phone is the main identifier in BD |
| Orders | orders | business\_id, customer\_id, number, status, source (manual, ai\_draft), subtotal, discount, delivery\_charge, cod\_amount, payment\_status | Money stored as integer poisha |
| Orders | order\_items | order\_id, variant\_id, qty, unit\_price, unit\_cost | Prices copied at order time |
| Orders | order\_events | order\_id, from\_status, to\_status, user\_id, note, created\_at | Audit trail per order |
| Orders | shipments | order\_id, courier, tracking\_no, status, cod\_collected, charge | One order can have a re-shipment |
| Orders | payments | order\_id, method (cod, bkash, nagad, card, bank), amount, status | Supports partial payments |
| Inbox | channels | business\_id, type (messenger, whatsapp), external\_id, access\_token (encrypted), status | One row per connected page or number |
| Inbox | conversations | business\_id, channel\_id, customer\_id, status, ai\_enabled, assigned\_to, last\_customer\_msg\_at | last\_customer\_msg\_at drives messaging-window rules |
| Inbox | messages | conversation\_id, direction, sender (customer, ai, staff), external\_msg\_id, body, attachments | external\_msg\_id unique, for deduplication |
| AI | ai\_settings | business\_id, instructions, tone, languages, policies (delivery, returns), auto\_reply\_on | Seller-editable |
| AI | ai\_runs | business\_id, conversation\_id, model, input\_tokens, output\_tokens, cost, tools\_called, outcome, counted | One row per AI attempt |
| Billing | plans | code, price, limits (json) | Limits as data |
| Billing | subscriptions | business\_id, plan\_id, status, period\_start, period\_end, gateway\_ref |  |
| Billing | usage\_counters | business\_id, period, metric, used, limit | ai\_replies, orders, products |
| Billing | invoices | business\_id, amount, status, gateway\_txn\_id |  |
| Platform | audit\_logs | business\_id, user\_id, action, entity, before, after | Security and support |

## Inventory and order logic

Available stock = on hand − reserved; an order reserves stock when confirmed and deducts it from on hand only when handed to the courier, so cancelled orders never need manual stock fixes.

&#91;embedded content: order states · 7 states and their stock effect\]

Any confirmed or packed order can be cancelled and its reservation released; a return can come from the courier (failed delivery) or from the customer after delivery.

Rules the code must enforce:

- Every stock change writes a `stock_movements` row in the same database transaction as the order change; the cached `on_hand` and `reserved` values update in that transaction.
- Reservation locks the variant row (`SELECT … FOR UPDATE`) so two staff or the AI cannot sell the last unit twice.
- AI drafts hold stock for 30 minutes, then release automatically through a scheduled job.
- Partial delivery is recorded as Delivered plus a return for the items the customer refused.
- On return, staff choose per item: restock (back to on hand) or write off (damaged); both are logged.
- Order prices and costs are copied onto order items, so later price edits do not change old orders or profit reports.
- Low-stock alert fires when available stock drops to or below the variant's threshold.
- CSV/Excel import runs in the worker: upload, map columns, preview the first 20 rows with errors, then commit; existing SKUs update instead of duplicating.

## AI agent design

The AI is a scoped sales assistant for one business: it can only read that business's catalog, stock, policies and the current customer's orders, and every write goes through a backend tool with validation.

Pipeline for each incoming customer message:

1. **Receive**: webhook endpoint verifies Meta's signature, stores the raw event, returns 200 within a second, and queues a job. No AI work happens inside the webhook request.
2. **Deduplicate**: skip if `external_msg_id` already exists (Meta retries webhooks).
3. **Debounce**: wait 3 to 5 seconds for follow-up messages, because customers often send "hi", "black tshirt", "L size" as three messages. Process them as one turn.
4. **Gate**: find the business from the channel; check subscription active, AI enabled for the business and this conversation, quota remaining, messaging window open. Any failure routes to the human inbox.
5. **Build context**: business instructions and policies, last 20 messages, the customer's recent orders, and a short catalog summary. Do not paste the whole catalog into the prompt; the model searches it with tools.
6. **Run the model with tools** (below), limited to 5 tool calls per turn.
7. **Validate the reply**: every price and stock figure in the reply must come from a tool result in this turn; block replies that mention products not returned by a tool.
8. **Send** through the channel API, store the message, write an `ai_runs` row, increment the usage counter.
9. **Handoff** when the model calls `handoff_to_human`, confidence is low, the customer asks for a person, or the customer is angry. The AI tells the customer a team member will reply and pauses itself for that conversation.

Tools the model can call:

| Tool | Does | Writes data? |
| --- | --- | --- |
| search\_products(query, category, size, color) | Finds matching variants with price and available stock | No |
| get\_product(variant\_id) | Full details, images, sizes in stock | No |
| get\_order\_status(order\_number or phone) | Status and tracking for this customer's orders only | No |
| get\_policy(topic) | Delivery charges by area, delivery time, return policy, payment methods | No |
| draft\_order(items, name, phone, address, area) | Creates an order with status Draft and a soft reservation for 30 minutes | Yes, draft only |
| send\_product\_images(variant\_id) | Sends product photos to the customer | No |
| handoff\_to\_human(reason) | Pauses AI and notifies staff | Conversation status |

Order rule: the AI reads the full order back to the customer (items, price, delivery charge, COD total, address) and calls `draft_order` only after the customer says yes. Staff confirm drafts in one click; a seller setting can later allow auto-confirm for trusted flows.

Language: replies follow the customer's language, including Bangla script, English and Banglish. Build a test set of 200 real Bangla and Banglish questions from interviewed sellers before launch.

Model and cost control:

- Put the model behind a provider interface so you can switch or mix providers.
- Use a small, fast model for routine turns and escalate to a stronger model only for long or complex conversations.
- Use prompt caching for the business instructions and policy block, which repeat on every turn.
- Log tokens and cost per run; alert when one business's cost exceeds its plan's margin.
- Seller controls: AI on/off, business hours only, topics to always hand off (for example price negotiation), and a review mode where AI drafts and staff send.

On WhatsApp, Meta's terms now only allow general-purpose AI assistants where Meta is legally required to permit them ([WhatsApp AI Providers policy](https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing/ai-providers)). Keep the agent strictly scoped to the business's own products and orders and have it decline unrelated questions.

## Messenger and WhatsApp integration

Launch with Messenger first, because most Bangladesh F-commerce sellers sell through Facebook pages and Messenger has no per-message fee; add WhatsApp once the AI flow is proven.

| Topic | Facebook Messenger | WhatsApp Business Platform (Cloud API) |
| --- | --- | --- |
| How a seller connects | Facebook Login for Business; seller picks the page; you store an encrypted page access token | Embedded Signup; seller links a business portfolio and phone number; you become their tech provider |
| Meta approvals you need | App Review for pages\_messaging and related page permissions; Business Verification of your company | Business Verification; tech provider onboarding; app review for WhatsApp permissions |
| Free-form reply window | 24 hours after the customer's last message | 24-hour customer service window, reset by each customer message |
| After the window | Human Agent tag lets a person reply up to 7 days after the customer's last message; automated replies cannot use it | Only pre-approved template messages |
| Message cost to the seller | No per-message fee | Per delivered message by category and recipient country; service replies became billable on Oct 1, 2026 after 1,000 free per phone number per month |
| Bangladesh pricing | — | Bangladesh is now its own market on Meta's rate card (moved out of Rest of Asia Pacific on Oct 1, 2026) |
| Who pays Meta | — | Usually the seller's own WhatsApp account and payment method in Meta Billing Hub; confirm in onboarding |

Sources: [Meta Messenger send-message rules](https://developers.facebook.com/docs/messenger-platform/send-messages), [WhatsApp Business Platform pricing](https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing).

What the system must do because of these rules:

- Store `last_customer_msg_at` per conversation and block AI sends outside 24 hours; offer staff the Human Agent path on Messenger and templates on WhatsApp.
- Read WhatsApp status webhooks for the pricing object, so the seller dashboard shows how many billable service messages they used.
- Warn Pro sellers on WhatsApp that each AI reply past 1,000 per month carries a Meta fee in addition to your plan, or bundle an estimate into the price.
- Handle token expiry and page disconnects: mark the channel as broken, email the owner, route messages to the inbox.
- Start Meta Business Verification and App Review in Phase 1, in parallel with building. Approval can take weeks and needs a working demo, a privacy policy and a data-deletion URL.

## Billing and payments

Start with prepaid monthly and yearly plans paid through a local gateway, and add automatic renewal later; Bangladesh sellers mostly pay by bKash or Nagad, and card-based recurring billing is uncommon.

| Option | What it gives you | Use it for |
| --- | --- | --- |
| SSLCommerz (aggregator) | One hosted checkout covering cards, bKash, Nagad, Rocket and net banking, with IPN callback and a validation API ([docs](https://sandbox-gw.sslcommerz.com/docs)) | MVP: one integration, every payment method |
| bKash tokenized checkout | A customer-approved agreement your server can charge again later | Release 2: auto-renewal for bKash payers |
| Manual bank transfer / invoice | Admin marks the invoice paid | Yearly plans and larger sellers |

Billing rules:

- Plans are prepaid for 1, 6 or 12 months; yearly gets 2 months free (placeholder).
- Renewal reminders 7, 3 and 1 day before expiry by email and in-app.
- Never trust the browser redirect: mark an invoice paid only after the gateway's server-side validation succeeds, and make the handler idempotent on the transaction ID.
- AI reply packs are one-time purchases that expire at the end of the billing period.
- Proration on upgrade: charge the price difference for remaining days; downgrades take effect at renewal.
- Keep a ledger of every payment and refund for reconciliation; export monthly for accounting.

Before launch, confirm with a local accountant the VAT treatment of SaaS subscriptions and whether you need a trade license and e-commerce registration for the business.

## Security and multi-tenancy

The one failure that can end the product is one seller seeing another seller's customers or orders, so tenant isolation is enforced in two layers and tested automatically.

- **Tenant isolation**: `business_id` on every row; the API resolves it from the logged-in membership, never from the request body; PostgreSQL row-level security as a second guard; an automated test suite that tries cross-tenant reads on every endpoint.
- **AI tools are tenant-bound**: tool functions receive the business and customer from the server context, never from the model's arguments.
- **Auth**: hashed passwords (Argon2 or bcrypt), phone OTP, optional two-factor for owners, short-lived access tokens with refresh tokens, rate limits on login and OTP.
- **Roles**: Owner (billing, staff, settings), Manager (everything except billing), Staff (orders, inbox, stock adjustments only); cost price and profit hidden from Staff.
- **Secrets**: Meta page tokens and WhatsApp credentials encrypted at rest with a key outside the database.
- **Webhooks**: verify Meta's signature header on every call; reject unsigned requests.
- **Prompt injection**: treat customer messages as untrusted; the system prompt forbids revealing instructions, other customers' data or cost prices; tool permissions enforce this even if the model is fooled.
- **Customer data**: customer phone numbers and addresses belong to the seller; provide export and deletion; publish a privacy policy and Meta's required data-deletion endpoint.
- **Operations**: daily encrypted database backups with a monthly restore test, audit log of sensitive actions, error tracking, uptime alerts.
- **Payments**: you never store card data; the gateway hosts checkout.

## Roadmap

The paid MVP (inventory, orders, billing) goes to pilot sellers around Feb 22, 2027 and the AI inbox around May 17, 2027, assuming one full-time developer starting Nov 2, 2026.

&#91;embedded content: build roadmap · 7 phases, 2 launches\]

Meta Business Verification and App Review run in parallel from Phase 1, so approval is ready when the AI work starts.

| Phase | Deliverables | Exit gate |
| --- | --- | --- |
| 0 Discovery, Meta setup (2 wks) | 8–10 seller interviews; final MVP scope; clickable mockups; company and Meta Business Verification started; repo, CI, staging server | Scope signed off; 5 sellers agree to pilot |
| 1 Foundation, inventory (5 wks) | Sign-up, OTP, businesses, staff roles, tenant isolation tests; products, variants, categories, images; stock ledger and adjustments; CSV/Excel import; low-stock alerts | A pilot seller imports their real catalog |
| 2 Orders, fulfillment (5 wks) | Customers; order creation; status workflow and reservation; payments and COD; returns and cancellations; packing slip PDF; dashboard | A pilot seller runs one week of real orders in it |
| 3 Billing, admin, QA (4 wks) | Plans and limits as data; usage counters; SSLCommerz checkout and invoices; platform admin; backups, monitoring, security review | First paid subscription goes through end to end |
| 4 AI inbox foundation (4 wks) | Inbox UI and real-time updates; AI settings; tools; reply validator; handoff; usage metering; test chat simulator with the 200-question Bangla/Banglish set | 90%+ correct on the test set; zero wrong stock or price answers |
| 5 Messenger pilot (4 wks) | Page connect flow; webhooks; send API; window rules; review mode; pilot with 5 sellers | 60%+ conversations resolved without handoff in pilot |
| 6 WhatsApp, hardening (4 wks) | Embedded Signup; templates; Meta usage display; overage packs; bKash auto-renewal; load and cross-tenant tests | Pro plan on sale to all sellers |

Add about 25% buffer for Meta delays, bugs and seller feedback; with part-time work, roughly double the calendar.

## Risks and mitigations

The biggest risks are Meta approval delays and AI replies that state wrong stock or prices; both are reduced by starting early and keeping humans in the loop at launch.

| Risk | Impact | Likelihood | Mitigation |
| --- | --- | --- | --- |
| Meta App Review or Business Verification is slow or rejected | AI inbox launch slips | High | Apply in Phase 1; record a clear demo video; keep the inbox usable with human replies only |
| AI states wrong stock, price or delivery charge | Lost trust, refunds | Medium | Tool-only facts, reply validator, review mode for the first 2 weeks per seller, weekly sampled audits |
| AI cost per Pro seller exceeds plan margin | Losses on heavy users | Medium | Per-run cost logging, small model by default, caching, hard quota, overage packs |
| WhatsApp per-message fees surprise sellers | Churn, support load | Medium | Show Meta usage in the dashboard; explain fees during onboarding; Messenger first |
| Sellers don't adopt structured inventory | Low retention | Medium | Excel import, quick-add from phone, sell the AI benefit that needs accurate stock |
| Cross-tenant data leak | Fatal for trust | Low | Two-layer isolation, automated cross-tenant tests, security review before launch |
| Overselling from concurrent orders | Angry customers | Medium | Row-level locking on reservation, reservation expiry, tests with parallel orders |
| Solo developer bandwidth | Timeline doubles | High | Strict MVP scope, managed services, defer P1/P2 items |
| Competing local tools already in market | Harder sales | Medium | Interview sellers on what they use now; compete on AI grounded in live stock |
| Meta policy or pricing changes again | Margin or feature changes | Medium | Meta updates pricing on quarter starts with notice; review each quarter; keep channels behind an adapter |

## Open decisions for review

These choices change scope or timeline; tick each one once decided and note the answer beside it.

- [x] Backend: Spring Boot (Java) or NestJS (TypeScript, one language across the stack)? spring boot
- [x] Team: one full-time developer as assumed, part-time, or a second developer for the frontend?part time
- [x] Market: Bangladesh only at launch, or also other countries (affects currency, language, gateways)? bangladesh
- [x] Pricing: are ৳0 / ৳990 / ৳2,990 in the right range? can edit
- [x] Starter plan: AI as a paid add-on, or AI only on Pro?ai only on pro
- [x] Stock timing: reserve stock at order confirm and deduct at handover to courier (as planned), or deduct at confirm? manually
- [x] AI orders: always staff-confirmed at launch (as planned), or allow auto-confirm for some sellers? both
- [x] WhatsApp fees: seller pays Meta directly (as planned), or you resell WhatsApp messaging inside the plan price? saller pay
- [x] Courier integrations: which couriers do your first sellers use, and is courier booking needed in the MVP?
- [x] Languages of the app itself: Bangla UI, English UI, or both at launch?both
- [x] Free plan: keep it permanently, or replace it with a 14-day Pro trial? 14 day pro trail
- [x] Company setup: is a registered business ready for Meta Business Verification and the payment gateway merchant account?

## Sources

- [Pricing on the WhatsApp Business Platform](https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing) (Meta, rates effective Oct 1, 2026)
- [Pricing policy for AI Providers on WhatsApp](https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing/ai-providers) (Meta)
- [Messenger Platform: send messages and messaging window](https://developers.facebook.com/docs/messenger-platform/send-messages) (Meta)
- [SSLCommerz developer documentation](https://sandbox-gw.sslcommerz.com/docs)
