# Load test report

Implementation-plan.md 13c: *50 concurrent staff creating and confirming orders on shared variants for 10 minutes; no oversell, ledger matches, p95 latency recorded.* Run on 29 October 2026 at commit `0054267` (Spring Boot 4.1.2), with `./mvnw test -Dtest=OrderLoadTests -Dload.minutes=10 -Dload.staff=50` (`backend/src/test/java/com/mojud/load/OrderLoadTests.java`).

## Result

**Passed.**
- **Errors:** none in 730,826 requests.
- **Stock:** never negative or over-reserved, in 1,200 samples taken straight from the database during the run.
- **At the end:**
  - every variant's reserved stock equalled the quantities of its confirmed orders exactly;
  - stock on hand equalled everything received;
  - the stock ledger matched the cached levels (the nightly reconciliation's own check);
  - all 244,375 order numbers were unique.
- **Speed (95% of requests at or under):** 67 ms to create an order, 42 ms to confirm one, 32 ms to cancel one.

## What the test does

- **Who:** 50 staff accounts, each signed in on its own session. They work in one business with two products of three sizes each (six variants).
- **Each staff member, as fast as they can for 10 minutes** (0 to 100 ms between orders):
  - drafts an order for a customer, with one or two variants, 1 to 3 of each;
  - confirms it, which reserves stock;
  - one time in four, cancels one of their earlier confirmed orders (releasing its stock);
  - cancels the draft if confirming found no stock.
- **The owner, meanwhile:** receives 10 more of a random variant every second.
- **The stock level:** starts at 60 per variant, so the shop is almost always selling its last units. Three confirmations in four found no stock. That is the hardest case for overselling: many people competing for the last few items.

## What the numbers mean

- **The load is far beyond a real shop's.** 50 staff with no pauses made 1,218 requests a second. A busy seller's whole team makes perhaps one a second.
- **The latencies are a floor for a server of this size.** The test client, the app and PostgreSQL shared one 16-thread machine, and the client's own work competes with the server's.
- **On the planned server** (2 vCPU, 2 GB), expect higher latencies at this load. At realistic load, expect the same correctness.
- **Database connections:** the app used its default 10. Nothing waited long enough to matter, so no tuning is needed now.
- **Not covered:** packing, shipping and returns, which move stock out. Their concurrency is covered by the order and stock tests from Phases 7 and 8. Run this test again before big changes to orders or stock (docs/operations.md).

## Measured

### Run

- 50 staff and one owner restocking, for 10.0 minutes, on 6 shared variants (60 units each at the start, +10 to one of them every second)
- Machine: 16 CPU threads, 15.5 GB memory, Linux; Java 17.0.20.1; PostgreSQL 18.6 (embedded); client, app and database on the same machine
- Started 2026-10-29T14:15:31.088630776Z

### Outcomes

| | Count |
|---|---:|
| cancel.ok | 60178 |
| confirm.ok | 63064 |
| confirm.out_of_stock | 181311 |
| orders created | 244375 |
| requests per second (all kinds) | 1218.0 |

### Latency (ms, measured by the client)

| Operation | Requests | p50 | p95 | p99 | max |
|---|---:|---:|---:|---:|---:|
| cancel | 241489 | 15 | 32 | 65 | 357 |
| confirm | 244375 | 19 | 42 | 102 | 430 |
| create | 244375 | 29 | 67 | 146 | 1104 |
| restock | 587 | 17 | 76 | 143 | 208 |

### Checks

- Server errors (5xx or unexpected answers): 0
- Stock sampled from the database 1200 times during the run; negative or over-reserved: 0
- Reserved equals the confirmed orders' quantities for every variant: yes
- On hand (6230) equals everything received (6230): yes
- Ledger (sum of stock movements) matches the cached levels: yes
- Order numbers unique: yes (244375 orders, 244375 numbers)

| Variant | On hand | Reserved | Confirmed orders' quantity |
|---|---:|---:|---:|
| 0af85f4e | 1090 | 1089 | 1089 |
| 2f6ac29c | 930 | 926 | 926 |
| 516c417a | 1050 | 1045 | 1045 |
| 9585c046 | 1060 | 1057 | 1057 |
| 4a72a066 | 1020 | 1011 | 1011 |
| 6c9ff252 | 1080 | 1077 | 1077 |
