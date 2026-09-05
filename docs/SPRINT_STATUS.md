# Sprint Status

Living progress tracker for the Ecommerce Order Processing System. Updated at the end of each sprint.

**Last updated:** Saturday, 5 September 2026, after Sprint 4
**Suite:** 131 tests, 0 failures, 0 skipped (`gradlew clean test`)

Plan: [`SPRINT_PLAN.md`](./SPRINT_PLAN.md) · Design: [`DESIGN.md`](./DESIGN.md) · Callable examples: [`API_EXAMPLES.md`](./API_EXAMPLES.md)

---

## 1. Sprint board

| Sprint | Goal | Requirements | Status | Tests |
|--------|------|--------------|--------|-------|
| **1** | Foundation and walking skeleton | 1, 2 | **Done** | 23 |
| **2** | Listing, filtering, pagination | 5 | **Done** | +53 → 76 |
| **3** | Cancellation and safe state transitions | 6 | **Done** | +31 → 107 |
| **4** | Background promotion job | 4 | **Done** | +24 → 131 |
| **5** | API docs, README, hardening | extras | Not started | — |

Progress: **4 of 5 sprints**, **all 6 requirements** functionally delivered.

---

## 2. Requirement coverage

| # | Requirement | State | Evidence |
|---|-------------|-------|----------|
| 1 | Create an order with multiple items | **Live** | `POST /api/v1/orders` → `201`; totals computed server-side |
| 2 | Retrieve order by order ID | **Live** | `GET /api/v1/orders/{orderId}` → `200` / `404` / `400` |
| 3 | Statuses `PENDING`…`DELIVERED` | **Live** | Five-value enum persisted as string; both transitions the brief allows are implemented. `SHIPPED` and `DELIVERED` are modelled with no API, by design (§1.1) |
| 4 | Background `PENDING` → `PROCESSING` each minute | **Live** | `@Scheduled` bulk update every 60s, interval configurable; count logged per run |
| 5 | List all orders, optional status filter | **Live** | `GET /api/v1/orders`, `?status=`, paging and sorting |
| 6 | Cancel only when `PENDING` | **Live** | `POST /api/v1/orders/{orderId}/cancel` → `200` / `409` / `404` / `400`, refused in SQL |

Requested extras: pagination **done** (Sprint 2); Swagger UI and README still open (Sprint 5).

---

## 3. Endpoints available today

| Method | Path | Behaviour |
|--------|------|-----------|
| `POST` | `/api/v1/orders` | `201` + `Location`; `400` on validation failure or duplicate `productId` |
| `GET` | `/api/v1/orders/{orderId}` | `200`; `404` unknown ID; `400` malformed UUID |
| `GET` | `/api/v1/orders` | `200` with the pagination envelope |
| `GET` | `/api/v1/orders?status=PENDING` | Filtered; `400` on an invalid or wrongly cased status |
| `GET` | `/api/v1/orders?page=0&size=2&sort=totalAmount,asc` | Paged and sorted; `400` on a non-whitelisted sort property |
| `POST` | `/api/v1/orders/{orderId}/cancel` | `200` with the cancelled order; `409` once it has left `PENDING`; `404` unknown ID; `400` malformed UUID |

Running alongside them, with no endpoint of its own: the promotion job moves every `PENDING` order to `PROCESSING` each minute. Set `orders.scheduler.promotion-rate-ms` to shorten the interval, or `orders.scheduler.enabled=false` to switch it off.

Not yet routed: `/swagger-ui.html` (Sprint 5).

---

## 4. Sprint 1 — Done

Requirements 1 and 2. All 11 planned tasks complete.

Delivered: JPA + H2 swap off MongoDB, `Order`/`OrderItem` entities with cascade and `orphanRemoval`, records for DTOs, `OrderMapper`, create with server-side `BigDecimal` totals and duplicate-SKU rejection, get-by-ID, and a `@RestControllerAdvice` giving every failure one JSON shape.

Verified live: `201` with `Location` and a computed total of `60.00`; `200` on re-read; `404` for an unknown UUID; `400` for empty items, blank `customerId`, zero quantity, malformed UUID, malformed JSON, and duplicate `productId`.

Two findings worth remembering:

- **A live-only bug.** `createdAt` came back with nanosecond precision on create but microsecond precision on a subsequent `GET`, because `TIMESTAMP(6)` rounds. No unit test could see it, since they used a whole-second clock. Timestamps are now truncated to microseconds at the service boundary.
- **Injection defence confirmed by hand.** A request sending `status: "DELIVERED"`, `totalAmount: 0.01` and a fabricated `cancelledAt` returned `PENDING`, `50.00` and `null`. The request DTO has no such fields to bind.

Accepted gaps: four documented edge cases (`@Digits`, `@Max`, negative quantity, and the injection case) are enforced in code but have no automated test. Recorded in `DESIGN.md` §17.5.

---

## 5. Sprint 2 — Done

Requirement 5 plus the pagination extra. All 8 planned tasks complete. Suite grew from 23 to **76** tests.

| Task | Outcome |
|------|---------|
| 2.1 | `PagedResponse<T>` envelope — `content`, `page`, `size`, `totalElements`, `totalPages`, `first`, `last`, `sort` |
| 2.2 | `findByStatus(status, Pageable)` alongside the inherited `findAll(Pageable)` |
| 2.3 | Sort whitelist: `createdAt`, `updatedAt`, `totalAmount`, `status` — anything else is `400` |
| 2.4 | Status parsed explicitly; invalid values `400` **listing the accepted values** |
| 2.5 | `page < 0` and `size < 1` are `400`; `size` above 100 is clamped |
| 2.6 | N+1 on `Order.items` fixed with `@BatchSize(50)` and pinned by a query-count test |
| 2.7 | Four indexes created, including compound `(status, created_at DESC)` |
| 2.8 | Repository paging tests, controller parameter tests, and a 7-case integration test |

### 5.1 The N+1 fix is measured, not asserted

`OrderListQueryCountTest` reads Hibernate's statistics while listing 5 orders and touching every item collection:

- **3 queries with `@BatchSize`** — one count, one page of orders, one batched item select
- **7 queries without it** — the classic one-select-per-order pattern

The test was run with `@BatchSize` removed to confirm it actually fails (`Expecting actual: 7L to be less than: 5L`) rather than passing regardless. A green assertion that cannot fail is worthless.

### 5.2 Why the sort whitelist matters

Handing an unvalidated property to a `Pageable` reaches Hibernate and surfaces as a `500`. Validating first turns `?sort=dropTable,asc` into:

```json
{"status":400,"message":"Cannot sort by 'dropTable'. Sortable properties: createdAt, status, totalAmount, updatedAt"}
```

The response names what *is* allowed, so the caller can correct it without reading the source.

### 5.3 Deliberate design choices

- **Custom envelope, not Spring's `Page`.** Serializing `PageImpl` produces a shape that has changed between Spring versions and triggers a warning.
- **No fetch join on the paged query.** Combining a collection fetch with a `Pageable` makes Hibernate paginate in memory; `@BatchSize` avoids that while still killing the N+1.
- **Explicit `@RequestParam`s rather than a resolved `Pageable`.** Spring's resolver silently clamps a negative page to 0, but the design calls for `400`. Parsing the parameters directly makes every documented response code reachable and unit-testable.
- **Schema claims are tested.** `created_at DESC` in an index declaration could be silently ignored, so a test queries `INFORMATION_SCHEMA.INDEXES` and asserts all four indexes exist.

### 5.4 Verified live

Seeded three orders and confirmed: the envelope reports `totalElements: 3`, `totalPages: 2`, `sort: "createdAt: DESC"` with the newest order first; `?status=SHIPPED` returns `200` with `content: []`; `?status=pending`, `?sort=dropTable`, `?page=-1` and `?size=0` each return `400`; and `?size=5000` is clamped to `"size":100`.

---

## 6. Sprint 3 — Done

Requirement 6. All 6 planned tasks complete. Suite grew from 76 to **107** tests.

| Task | Outcome |
|------|---------|
| 3.1 | `InvalidOrderStateException` with a `cannotCancel(status)` factory, separate from `OrderNotFoundException` |
| 3.2 | `cancelIfInStatus(id, expectedStatus, now)` — one conditional `UPDATE`, `@Modifying(clearAutomatically, flushAutomatically)` |
| 3.3 | Service decides on the affected row count; on `0` it re-reads to tell `404` from `409` |
| 3.4 | `InvalidOrderStateException` → `409 Conflict` in the existing advice |
| 3.5 | `POST /api/v1/orders/{orderId}/cancel` |
| 3.6 | 31 new tests: repository conditional-update branches, service `409`/`404`, controller status codes, 9-case cancel integration suite |

### 6.1 The rule lives in SQL, not in an `if`

The whole point of the sprint. Cancel issues one statement:

```sql
UPDATE orders
   SET status = 'CANCELLED', cancelled_at = ?, updated_at = ?, version = version + 1
 WHERE id = ? AND status = 'PENDING';
```

The affected row count is the decision. `1` means cancelled. `0` means either no such order or one that has already advanced, and only then does the service re-read to choose between `404` and `409`. The tempting `if (status == PENDING) save(...)` would leave a window in which Sprint 4's promotion job could resurrect a cancelled order, which is exactly the bug §5.4 of the design exists to prevent.

`OrderRepositoryTest` proves the predicate does the refusing: the update returns `0` for `PROCESSING`, `SHIPPED`, `DELIVERED` and `CANCELLED` rows, and each row keeps its original status, `cancelledAt` and `version`.

### 6.2 Two JPA traps that were designed around

- **Bulk JPQL bypasses the persistence context.** Without `clearAutomatically`, the response would be mapped from a cached entity that still said `PENDING`. The service re-reads after the update, and the test asserting the response says `CANCELLED` would fail if the flag were dropped.
- **Bulk updates bypass `@Version` and entity callbacks.** `version` and `updated_at` are therefore advanced inside the statement, and a repository test asserts `version` really did increment.

### 6.3 Verified live

Against a running app: cancelling a `PENDING` order returned `200` with `status: CANCELLED` and `cancelledAt` equal to `updatedAt`; a second cancel returned `409` with `"Current status: CANCELLED"`; an unknown UUID returned `404`; `not-a-uuid` returned `400`; and the order moved from the `PENDING` filter to the `CANCELLED` one. The smoke scripts grew from 22 to 29 checks and all pass.

---

## 7. Sprint 4 — Done

Requirement 4. All 6 planned tasks complete. Suite grew from 107 to **131** tests.

| Task | Outcome |
|------|---------|
| 4.1 | `@EnableScheduling` on the application class |
| 4.2 | `promoteAllPending(now)` — one bulk `UPDATE ... WHERE status = 'PENDING'`, no per-row loop |
| 4.3 | `OrderService.promotePendingOrders()` returns the count and is `@Transactional` |
| 4.4 | `PendingOrderPromotionJob` with `fixedRateString` from config, exceptions contained, count logged |
| 4.5 | `test` profile activated by the Gradle `test` task, with the scheduler switched **off** for the suite |
| 4.6 | 24 new tests, including both orderings of cancel versus promote |

### 7.1 The race regression, from both directions

The test the plan called the most valuable in the suite, now that both sides exist:

- **Cancel first, then a run.** The order stays `CANCELLED` and the run reports `0` promoted. A read-then-write scheduler would reload the order, still see `PENDING` in its own snapshot, and write `PROCESSING` over the cancellation.
- **Run first, then cancel.** The order is `PROCESSING` and the cancel returns `409` naming that status.

Both are covered twice: at the repository level, where the two statements are issued directly, and end to end through HTTP.

### 7.2 The scheduler is off during the test suite

§10 of the design proposed a `test` profile with a *fast* scheduler. That turned out to be the wrong default. `@SpringBootTest` contexts are cached and outlive the class that created them, so a live scheduler keeps ticking underneath every later test and flips the `PENDING` orders they assert on. The suite therefore sets `orders.scheduler.enabled=false`, and the one test that needs real wiring turns it back on for itself and discards its context afterwards with `@DirtiesContext`.

### 7.3 The wiring test has teeth

A missing `@EnableScheduling` is the classic silent failure: every unit test passes and nothing ever runs. `PendingOrderPromotionSchedulingTest` runs with a 50 ms interval, persists a `PENDING` order and polls until the scheduler moves it, touching no service method itself.

Confirmed by commenting out `@EnableScheduling` and re-running: both of its tests fail. With the annotation restored, all 131 pass.

### 7.4 Verified live

Against a running app with a five-second interval: an untouched order went `PENDING` → `PROCESSING` on the next run; an order cancelled first was still `CANCELLED` after several runs; cancelling the promoted order returned `409`. The log carried exactly one line per productive run — `Promoted 1 pending order(s) to PROCESSING` on the `scheduling-1` thread — and nothing on idle runs.

---

## 8. Test inventory

| Suite | Tests | Covers |
|-------|-------|--------|
| `OrderControllerTest` | 28 | Every status code and JSON shape for all four endpoints |
| `OrderRepositoryTest` | 27 | Cascade, `orphanRemoval`, paging, sorting, indexes, conditional cancel, bulk promote, both race orderings |
| `OrderServiceImplTest` | 24 | Totals, rounding, duplicate SKUs, filter routing, cancel row-count branches, promote count |
| `OrderQueryParamsTest` | 23 | Status parsing, sort whitelist, paging bounds, clamping |
| `OrderCancelIntegrationTest` | 9 | HTTP → H2 → HTTP for cancel, double cancel, advanced orders, filter movement |
| `OrderListIntegrationTest` | 7 | HTTP → H2 → HTTP for filtering, slicing and ordering |
| `OrderPromotionIntegrationTest` | 6 | Promotion end to end, batch promotion, and the cancel-versus-promote race |
| `PendingOrderPromotionJobTest` | 3 | Delegation, error containment, quiet empty run |
| `PendingOrderPromotionSchedulingTest` | 2 | `@Scheduled` really registered and really firing |
| `OrderListQueryCountTest` | 1 | N+1 regression guard |
| `OrderManagementSystemApplicationTests` | 1 | Context loads |
| **Total** | **131** | 0 failures, 0 skipped |

---

## 9. Open items

| Item | Where it lands |
|------|----------------|
| Swagger UI (`springdoc` **3.1.0** — the `2.8.x` line is Boot 3 only) | Sprint 5 |
| README | Sprint 5 |
| Four accepted test gaps from Sprint 1 | Deferred, `DESIGN.md` §17.5 |

---

## 10. How to verify the current state

```powershell
# Build machine notes: JAVA_HOME must be set, and Avast's TLS interception
# requires the Windows trust store. See DESIGN.md 17.3.
$env:JAVA_HOME = "C:\Users\ACER\.jdks\graalvm-ce-25.0.2"
.\gradlew.bat clean test "-Dorg.gradle.jvmargs=-Djavax.net.ssl.trustStoreType=Windows-ROOT"

# The smoke script asserts on PENDING orders, so keep the job out of its way
.\gradlew.bat bootRun "--args=--orders.scheduler.enabled=false" "-Dorg.gradle.jvmargs=-Djavax.net.ssl.trustStoreType=Windows-ROOT"
.\docs\smoke-test.ps1

# To watch the job instead, restart with a short interval
.\gradlew.bat bootRun "--args=--orders.scheduler.promotion-rate-ms=5000" "-Dorg.gradle.jvmargs=-Djavax.net.ssl.trustStoreType=Windows-ROOT"
```

`bash docs/smoke-test.sh` is the equivalent on a Unix shell. Both run 29 checks and exit non-zero on the first mismatch. Data lives in an in-memory H2 database, so restarting the app clears every order.
