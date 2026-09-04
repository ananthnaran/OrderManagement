# Ecommerce Order Processing System — Sprint Plan

Incremental delivery plan for the assignment. Companion to [`DESIGN.md`](./DESIGN.md); section references like §5.4 point into that document. **No implementation code here.**

**Approach:** five short sprints, each a **vertical slice** that ends in something runnable and demoable. Nothing is built "half now, half later" across a sprint boundary except where a genuine dependency forces it.

**Total estimate:** ~34 focused hours / 28 points across 5 sprints.

---

## 1. Sprint overview

| Sprint | Goal | Requirements covered | Points | Est. | Demoable outcome |
|--------|------|----------------------|--------|------|------------------|
| **1** | Foundation and walking skeleton | 1, 2 | 8 | ~10h | Create an order with multiple items and read it back from H2 |
| **2** | Listing, filtering, pagination | 5 | 5 | ~7h | List orders, filter by status, page and sort |
| **3** | Cancellation and safe state transitions | 6 | 5 | ~5.5h | Cancel a `PENDING` order; `409` on anything else |
| **4** | Background promotion job | 4 | 5 | ~5.5h | `PENDING` becomes `PROCESSING` automatically each minute |
| **5** | API docs, README, hardening | — | 5 | ~6h | Swagger UI, README, full edge-case sweep |

Requirement 3 (the status enum) is not a sprint of its own. The enum lands in Sprint 1 and each later sprint activates another part of the state machine.

### 1.1 Why this order

The sequence is driven by two real dependencies, not preference:

- **Sprint 3 before Sprint 4.** Cancel is where the status-conditioned update pattern (§5.4) gets built and tested. The background job reuses that exact pattern, so building cancel first means the job is a small, safe addition rather than a new mechanism.
- **The race regression test can only exist after Sprint 4.** Cancel-versus-promote needs both sides present. It is the closing test of Sprint 4, and it is the single most valuable test in the suite.

Everything else follows read-before-write: create and get (Sprint 1) give every later sprint its test fixtures.

---

## 2. Sprint 1 — Foundation and walking skeleton

**Goal:** a running Spring Boot app that persists an order with multiple line items to H2 and reads it back, with correct validation and a consistent error shape.

**Covers:** requirement 1 (create with multiple items), requirement 2 (retrieve by ID).

### 2.1 Stories

**S1.1 — Swap the persistence stack to JPA + H2**
The project currently ships with MongoDB starters (§12) and cannot compile against a relational design.
*Acceptance:* `./gradlew build` resolves; no Mongo dependency or import remains; app starts and logs an H2 connection.

**S1.2 — As a client, I can create an order with multiple items**
*Acceptance:*
- Given a valid body with two items, when I `POST /api/v1/orders`, then I get `201`, a `Location` header, status `PENDING`, and a server-computed `totalAmount`.
- Given `items: []`, then `400` with field details.
- Given `quantity: 0` or a negative `unitPrice`, then `400`.
- Given a body sending `status` or `totalAmount`, then those values are ignored.
- Given the same `productId` twice, then `400` (§7.1).

**S1.3 — As a client, I can retrieve an order by ID**
*Acceptance:* known ID returns `200` with all items; unknown but well-formed UUID returns `404`; malformed UUID returns `400`.

### 2.2 Tasks

| # | Task | Design ref | Est. |
|---|------|-----------|------|
| 1.1 | Remove Mongo starters; add `spring-boot-starter-data-jpa`, `h2`, JPA test starter | §12 | 0.5h |
| 1.2 | `application.properties`: H2 URL, `ddl-auto`, `open-in-view=false` | §12.1 | 0.25h |
| 1.3 | `OrderStatus` enum, all five values, `EnumType.STRING` | §4.1 | 0.25h |
| 1.4 | `Order` and `OrderItem` entities: cascade, `orphanRemoval`, `@Version`, `DECIMAL(12,2)` columns | §4.2, §4.3 | 1.5h |
| 1.5 | `OrderRepository` | §6 | 0.25h |
| 1.6 | Request/response DTOs and `ErrorResponse` | §3.1–3.5 | 1h |
| 1.7 | `OrderMapper` (entity ↔ DTO) | §6 | 0.5h |
| 1.8 | `OrderService`: create (totals, `HALF_UP`, duplicate-SKU check) and `getById` | §7.1, §7.3 | 1.5h |
| 1.9 | Exceptions + `GlobalExceptionHandler` for `400` / `404` / `500` | §10 | 1h |
| 1.10 | `OrderController`: `POST` and `GET`, `201` + `Location` | §3 | 0.75h |
| 1.11 | Tests: service unit, `@WebMvcTest`, `@DataJpaTest` cascade persist | §13.1–13.3 | 2.5h |

### 2.3 Demo script

```bash
# 201 + Location, status PENDING, totalAmount 60.00
curl -i -X POST localhost:8080/api/v1/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"CUST-1001","items":[
       {"productId":"SKU-MOUSE","productName":"Wireless Mouse","quantity":2,"unitPrice":25.00},
       {"productId":"SKU-PAD","productName":"Mouse Pad","quantity":1,"unitPrice":10.00}]}'

curl localhost:8080/api/v1/orders/<id>          # 200
curl localhost:8080/api/v1/orders/<random-uuid> # 404
curl -X POST localhost:8080/api/v1/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"","items":[]}'             # 400 with details
```

### 2.4 Exit criteria

`./gradlew test` green; create and get work end to end; every error path returns the §3.5 body, never a stack trace.

### 2.5 Sprint risks

| Risk | Mitigation |
|------|-----------|
| Boot 4 modular starter names differ from Boot 3 habits (§12) | Do task 1.1 **first and alone**, confirm the build resolves before writing any entity code |
| `@Valid` omitted on the items collection, so nested constraints silently skip | Explicit test: invalid `quantity` inside an otherwise valid body must return `400` |
| `BigDecimal` assertions failing on scale (`60.0` vs `60.00`) | Compare with `isEqualByComparingTo`, never `equals` (§13.1) |

---

## 3. Sprint 2 — Listing, filtering, pagination

**Goal:** list orders with an optional status filter, stable pagination, and validated sorting.

**Covers:** requirement 5, plus the requested pagination extra.

**Depends on:** Sprint 1 (needs persisted orders to list).

### 3.1 Stories

**S2.1 — As a client, I can list all orders**
*Acceptance:* `200` with the §3.3 envelope, newest first; no orders yields `content: []` and `totalElements: 0`, not `404`.

**S2.2 — As a client, I can filter the list by status**
*Acceptance:* `?status=PENDING` returns only pending orders; `?status=pending` or `?status=FOO` returns `400` listing the accepted values.

**S2.3 — As a client, I can page and sort the list**
*Acceptance:* `?page=0&size=2` returns 2 items with correct `totalPages`/`first`/`last`; a page past the end returns `200` with empty content; `size` over 100 clamps; negative `page` returns `400`; `?sort=nonsense` returns `400`, not `500`.

### 3.2 Tasks

| # | Task | Design ref | Est. |
|---|------|-----------|------|
| 2.1 | `PagedResponse<T>` envelope (avoid serializing `PageImpl`) | §3.3 | 0.5h |
| 2.2 | Repository `findAll(Pageable)` / `findByStatus(status, Pageable)` | §6 | 0.25h |
| 2.3 | Sort-property whitelist → `400` on unknown property | §3.4 | 1h |
| 2.4 | `status` param binding; invalid enum → `400` | §7.2 | 0.75h |
| 2.5 | `page` / `size` validation and max-page-size clamp | §3.4, §12.1 | 0.5h |
| 2.6 | Address N+1 on `items` (`@BatchSize`), verify query counts | §4.4 | 1h |
| 2.7 | Add the three `orders` indexes incl. compound `(status, created_at DESC)` | §5.2 | 0.5h |
| 2.8 | Tests: repo paging/sort, controller `400`s, integration slicing | §13.2–13.4 | 2.5h |

### 3.3 Demo script

```bash
curl 'localhost:8080/api/v1/orders?page=0&size=2'              # envelope, 2 of N
curl 'localhost:8080/api/v1/orders?status=PENDING'             # filtered
curl 'localhost:8080/api/v1/orders?sort=totalAmount,desc'      # allowed sort
curl 'localhost:8080/api/v1/orders?sort=dropTable'             # 400
curl 'localhost:8080/api/v1/orders?status=pending'             # 400
```

### 3.4 Exit criteria

Pagination metadata is correct against a known dataset; no unvalidated input reaches `Pageable`; listing N orders does not issue N+1 queries.

### 3.5 Sprint risks

| Risk | Mitigation |
|------|-----------|
| Unknown sort property surfaces as a Hibernate `500` (§3.4) | Whitelist first, then a test asserting `400` |
| Collection fetch-join + `Pageable` paginates in memory (§4.4) | Use `@BatchSize` rather than a fetch-join on the paged query |
| Shared in-memory H2 leaks rows between tests and breaks page counts | Rollback or explicit cleanup per test (§13.4) |

---

## 4. Sprint 3 — Cancellation and safe state transitions

**Goal:** cancel an order only while `PENDING`, enforced in SQL rather than an in-memory check.

**Covers:** requirement 6.

**Depends on:** Sprint 1.

### 4.1 Stories

**S3.1 — As a client, I can cancel a PENDING order**
*Acceptance:* `POST /{id}/cancel` on a pending order returns `200`, status `CANCELLED`, `cancelledAt` set and persisted.

**S3.2 — Cancelling a non-PENDING order is rejected**
*Acceptance:* `PROCESSING`, `SHIPPED`, `DELIVERED` and already-`CANCELLED` all return `409` with the current status in the message; the row is unchanged. An unknown ID returns `404`, and the two cases are never confused.

### 4.2 Tasks

| # | Task | Design ref | Est. |
|---|------|-----------|------|
| 3.1 | `InvalidOrderStateException` | §6 | 0.25h |
| 3.2 | Conditional cancel update (`WHERE id = ? AND status = 'PENDING'`) with `@Modifying(clearAutomatically, flushAutomatically)` | §5.4 | 1h |
| 3.3 | Service cancel: interpret affected row count; on `0`, re-read to split `404` from `409` | §5.4 | 1h |
| 3.4 | Map `InvalidOrderStateException` → `409` in the handler | §3.6 | 0.25h |
| 3.5 | Controller `POST /{orderId}/cancel` | §3 | 0.5h |
| 3.6 | Tests: `@DataJpaTest` update returns **0** on a non-pending row; unit `409`/`404`; integration cancel-then-get | §13.3, §13.4 | 2.5h |

### 4.3 Demo script

```bash
curl -X POST localhost:8080/api/v1/orders/<pending-id>/cancel   # 200, CANCELLED
curl -X POST localhost:8080/api/v1/orders/<pending-id>/cancel   # 409, already cancelled
curl 'localhost:8080/api/v1/orders?status=CANCELLED'            # appears in the filter
```

### 4.4 Exit criteria

Cancel is decided by the affected row count, not a read-then-write; the `@DataJpaTest` proving the update returns `0` on a non-pending row exists and passes.

### 4.5 Sprint risks

| Risk | Mitigation |
|------|-----------|
| Bulk update bypasses the persistence context, so the response echoes a stale status | `clearAutomatically`/`flushAutomatically`, then re-read before mapping the response (§5.4) |
| Tempting shortcut: `if (status == PENDING) save(...)` | Rejected by design — it reintroduces the lost update Sprint 4 must survive |
| `0` rows treated as a blanket `404`, masking the conflict | Explicit re-read to distinguish, with a test for each branch |

---

## 5. Sprint 4 — Background promotion job

**Goal:** `PENDING` orders become `PROCESSING` automatically every minute, without ever overwriting a cancellation.

**Covers:** requirement 4.

**Depends on:** Sprint 3 (reuses the conditional-update pattern and enables the race test).

### 5.1 Stories

**S4.1 — Pending orders are promoted automatically every minute**
*Acceptance:* a `PENDING` order becomes `PROCESSING` within one interval; `SHIPPED`, `DELIVERED` and `CANCELLED` orders are untouched; a tick with nothing pending is a no-op that logs a count of `0`; a failing tick is logged and does not kill the scheduler thread.

**S4.2 — The job and a concurrent cancel cannot corrupt each other**
*Acceptance:* an order cancelled before a tick stays `CANCELLED` (never resurrected as `PROCESSING`); an order promoted before a cancel attempt yields `409` and stays `PROCESSING`.

### 5.2 Tasks

| # | Task | Design ref | Est. |
|---|------|-----------|------|
| 4.1 | `@EnableScheduling` on the application class | §9 | 0.25h |
| 4.2 | Bulk promote update (`WHERE status = 'PENDING'`), single statement | §5.4 | 0.75h |
| 4.3 | `OrderService.promotePendingOrders()` returning the count, `@Transactional` | §9 | 0.5h |
| 4.4 | `PendingOrderPromotionJob`: `fixedRateString` from config, error containment, count logging | §9 | 1h |
| 4.5 | `test` profile with a short promotion interval | §12.1 | 0.5h |
| 4.6 | Tests: promote only `PENDING`; none-pending no-op; scheduling actually enabled; **cancel-then-promote stays CANCELLED**; promote-then-cancel → `409` | §13.4, §13.5 | 2.5h |

### 5.3 Demo script

```bash
curl -X POST localhost:8080/api/v1/orders -H 'Content-Type: application/json' -d '{...}'
# wait one interval, or call the service method from a test
curl 'localhost:8080/api/v1/orders?status=PROCESSING'   # the new order is here
curl 'localhost:8080/api/v1/orders?status=PENDING'      # empty
```

### 5.4 Exit criteria

Requirements 1–6 are all functionally complete. The cancel-then-promote regression test passes. No test sleeps for a minute.

### 5.5 Sprint risks

| Risk | Mitigation |
|------|-----------|
| A missing `@EnableScheduling` fails silently — all unit tests pass and nothing ever runs | A dedicated test asserting scheduling is enabled (§13.5) |
| A 60-second `Thread.sleep` in tests makes the suite unusable | Call `promotePendingOrders()` directly; short interval only in the `test` profile |
| Per-row loop instead of one bulk statement reopens the race | One `UPDATE` for the whole batch (§5.4) |

---

## 6. Sprint 5 — API docs, README, hardening

**Goal:** a reviewer can clone, run, and exercise the API within five minutes and can see the reasoning behind it.

**Covers:** the Swagger and README extras, plus a final quality pass.

### 6.1 Stories

**S5.1 — Interactive API documentation**
*Acceptance:* `/swagger-ui.html` lists all five endpoints with request/response examples, and documents the `400` / `404` / `409` responses — not just the happy path. `/v3/api-docs` serves valid OpenAPI 3.

**S5.2 — README**
*Acceptance:* covers run and test commands, the endpoint table with working `curl` calls, the status machine, design decisions including why `CANCELLED` was added, and known limitations (§14).

**S5.3 — Edge-case sweep**
*Acceptance:* every row of §8 has either a passing test or an explicit note explaining why it is out of scope.

### 6.2 Tasks

| # | Task | Design ref | Est. |
|---|------|-----------|------|
| 5.1 | Add `springdoc-openapi-starter-webmvc-ui:3.1.0` and the two enable properties | §11 | 0.5h |
| 5.2 | `OpenApiConfig` metadata bean | §11 | 0.5h |
| 5.3 | `@Tag`, `@Operation`, `@ApiResponses` (incl. failures), `@Schema` examples | §11 | 1.5h |
| 5.4 | Write the README | §14 | 1.5h |
| 5.5 | Walk the §8 edge-case table, close any gaps | §8 | 1h |
| 5.6 | Logging pass, final full test run, coverage sanity check | §10, §13 | 1h |

### 6.3 Exit criteria

Swagger UI renders; README is accurate against the running app; full suite green; roughly 25–30 tests as estimated in §13.7.

### 6.4 Sprint risks

| Risk | Mitigation |
|------|-----------|
| Copying `springdoc` `2.8.x` from an older tutorial — it supports Boot 3 only and silently fails to auto-configure | Pin `3.1.0` and verify the UI loads as the first check (§11) |
| Boot 4 needs the springdoc enable properties set explicitly | Both properties are part of task 5.1, not an afterthought |
| README drifting from actual behaviour | Write it last, and paste `curl` output actually observed |

---

## 7. Requirement traceability

| Requirement | Sprint | Verified by |
|-------------|--------|-------------|
| 1. Create order with multiple items | 1 | Service unit + `@WebMvcTest` `201` + integration round-trip |
| 2. Retrieve order by ID | 1 | `200` / `404` / `400` malformed UUID tests |
| 3. Four order statuses | 1 (enum), activated in 3 and 4 | Enum persisted as string; status filter tests |
| 4. Background `PENDING` → `PROCESSING` each minute | 4 | Promote unit tests + scheduling-enabled test + race regression |
| 5. List all orders, optional status filter | 2 | Repo paging tests, filter tests, invalid-status `400` |
| 6. Cancel only when `PENDING` | 3 | `@DataJpaTest` conditional update, `409` and `404` branches |
| *Extra:* pagination and sorting | 2 | Envelope metadata + sort whitelist `400` |
| *Extra:* Swagger UI | 5 | UI renders, spec valid |
| *Extra:* README | 5 | Manual review against the running app |

---

## 8. Definition of Ready / Definition of Done

**Ready** — a story can be started when its acceptance criteria are written as observable outcomes, the design section it implements is identified, and its dependency sprint is complete.

**Done** — a story is complete when:

1. Code matches the agreed design; deviations are recorded in `DESIGN.md` rather than left as surprises.
2. Unit tests cover the happy path and every documented failure path.
3. The relevant §8 edge cases have tests.
4. `./gradlew test` is green — no skipped or commented-out tests.
5. Error responses use the single §3.5 shape, with no stack traces or SQL leaked.
6. No entity crosses the HTTP boundary; no client-supplied status or total is trusted.
7. `BigDecimal` is used for money and `Instant` for time.
8. The demo script for that sprint runs clean against a fresh start.

---

## 9. Backlog (deliberately not scheduled)

Listed so the scope boundary is a visible decision rather than an omission. These belong in the README's "next steps".

| Item | Why deferred |
|------|--------------|
| Authentication / authorization | Not requested; orders are unowned in v1 |
| Flyway migrations replacing `ddl-auto` | Correct for production; `ddl-auto` is fine for in-memory H2 |
| PostgreSQL via Docker Compose | H2 keeps the reviewer's setup to one command |
| `Idempotency-Key` on create | Needs a stored request-hash table; disproportionate here |
| Public `SHIPPED` / `DELIVERED` transition APIs | Not in the brief; enum values already exist |
| Actuator, metrics, rate limiting | Operational concerns, out of scope |
| Distributed scheduler locking (ShedLock) | Only matters on multiple instances; single instance assumed (§9) |
| Order search by customer, date range | Not requested |

---

## 10. If time runs short

The sprints are ordered so that stopping early still leaves a coherent submission, but the priority if the schedule compresses is:

1. **Sprints 1, 3, 4 are non-negotiable** — they are requirements 1, 2, 4 and 6, including the concurrency guard that makes the design defensible.
2. **Sprint 2's list endpoint is required (requirement 5); its pagination is the optional part.** Ship the filter first, then paging.
3. **Write the README even if Swagger is dropped.** Reasoning that a reviewer can read beats an interactive page they might not open.
4. **Never trade away the tests in Sprint 3.6 and 4.6.** They are the evidence that the status machine actually holds.
