# Sprint Status

Living progress tracker for the Ecommerce Order Processing System. Updated at the end of each sprint.

**Last updated:** Friday, 4 September 2026, after Sprint 2
**Suite:** 76 tests, 0 failures, 0 skipped (`gradlew clean test`)

Plan: [`SPRINT_PLAN.md`](./SPRINT_PLAN.md) · Design: [`DESIGN.md`](./DESIGN.md) · Callable examples: [`API_EXAMPLES.md`](./API_EXAMPLES.md)

---

## 1. Sprint board

| Sprint | Goal | Requirements | Status | Tests |
|--------|------|--------------|--------|-------|
| **1** | Foundation and walking skeleton | 1, 2 | **Done** | 23 |
| **2** | Listing, filtering, pagination | 5 | **Done** | +53 → 76 |
| **3** | Cancellation and safe state transitions | 6 | Not started | — |
| **4** | Background promotion job | 4 | Not started | — |
| **5** | API docs, README, hardening | extras | Not started | — |

Progress: **2 of 5 sprints**, **3 of 6 requirements** fully delivered.

---

## 2. Requirement coverage

| # | Requirement | State | Evidence |
|---|-------------|-------|----------|
| 1 | Create an order with multiple items | **Live** | `POST /api/v1/orders` → `201`; totals computed server-side |
| 2 | Retrieve order by order ID | **Live** | `GET /api/v1/orders/{orderId}` → `200` / `404` / `400` |
| 3 | Statuses `PENDING`…`DELIVERED` | **Modelled** | Full five-value enum persisted as string; `PENDING` set on create; the rest activate in Sprints 3–4 |
| 4 | Background `PENDING` → `PROCESSING` each minute | Not started | Sprint 4 |
| 5 | List all orders, optional status filter | **Live** | `GET /api/v1/orders`, `?status=`, paging and sorting |
| 6 | Cancel only when `PENDING` | Not started | Sprint 3 |

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

Not yet routed: `POST /api/v1/orders/{id}/cancel` (Sprint 3) and `/swagger-ui.html` (Sprint 5).

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

## 6. Test inventory

| Suite | Tests | Covers |
|-------|-------|--------|
| `OrderQueryParamsTest` | 23 | Status parsing, sort whitelist, paging bounds, clamping |
| `OrderControllerTest` | 21 | Every status code and JSON shape for all three endpoints |
| `OrderServiceImplTest` | 12 | Totals, rounding, duplicate SKUs, filter routing, envelope metadata |
| `OrderRepositoryTest` | 11 | Cascade, `orphanRemoval`, filtering, paging, sorting, index creation |
| `OrderListIntegrationTest` | 7 | HTTP → H2 → HTTP for filtering, slicing and ordering |
| `OrderListQueryCountTest` | 1 | N+1 regression guard |
| `OrderManagementSystemApplicationTests` | 1 | Context loads |
| **Total** | **76** | 0 failures, 0 skipped |

---

## 7. Open items

| Item | Where it lands |
|------|----------------|
| Cancel endpoint with the status-conditioned update | Sprint 3 |
| Scheduled `PENDING` → `PROCESSING` promotion | Sprint 4 |
| The cancel-versus-promote race regression test | Sprint 4 (needs both sides present) |
| Swagger UI (`springdoc` **3.1.0** — the `2.8.x` line is Boot 3 only) | Sprint 5 |
| README | Sprint 5 |
| Four accepted test gaps from Sprint 1 | Deferred, `DESIGN.md` §17.5 |

---

## 8. How to verify the current state

```powershell
# Build machine notes: JAVA_HOME must be set, and Avast's TLS interception
# requires the Windows trust store. See DESIGN.md 17.3.
$env:JAVA_HOME = "C:\Users\ACER\.jdks\graalvm-ce-25.0.2"
.\gradlew.bat clean test "-Dorg.gradle.jvmargs=-Djavax.net.ssl.trustStoreType=Windows-ROOT"

.\gradlew.bat bootRun "-Dorg.gradle.jvmargs=-Djavax.net.ssl.trustStoreType=Windows-ROOT"
.\docs\smoke-test.ps1
```

`bash docs/smoke-test.sh` is the equivalent on a Unix shell. Data lives in an in-memory H2 database, so restarting the app clears every order.
