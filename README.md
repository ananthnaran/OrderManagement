# Ecommerce Order Processing System

A REST API for placing and tracking customer orders, built with Java and Spring Boot as a
take-home assignment. Orders are created with multiple line items, listed and filtered by status,
cancelled while they are still pending, and promoted from `PENDING` to `PROCESSING` by a background
job. Totals and status are owned by the server; the client cannot set either.

The interesting part of the implementation is not the CRUD — it is that cancellation and the
background job can both target the same order at the same moment, and the design makes that safe in
SQL rather than in application code. See [The one decision worth reading](#the-one-decision-worth-reading).

**Stack:** Java 25, Spring Boot 4.1.1, Spring Web MVC, Spring Data JPA (Hibernate 7), H2 in-memory,
Bean Validation, Lombok, springdoc-openapi 3.1.0, JUnit 5.

Java 25 rather than the 17 originally planned, because no JDK 17 exists on the build machine and
Gradle toolchains fail rather than silently substituting. Boot 4's floor is 17, so moving back down
only requires installing that JDK.

---

## Requirement coverage

| # | Requirement | Where |
|---|-------------|-------|
| 1 | Create an order with multiple items | `POST /api/v1/orders` |
| 2 | Retrieve an order by ID | `GET /api/v1/orders/{orderId}` |
| 3 | Statuses `PENDING`, `PROCESSING`, `SHIPPED`, `DELIVERED` | `OrderStatus`, plus `CANCELLED` — see [design decisions](#design-decisions-and-trade-offs) |
| 4 | Background job: `PENDING` → `PROCESSING` every minute | `PendingOrderPromotionJob` |
| 5 | List all orders, optionally filtered by status | `GET /api/v1/orders?status=` |
| 6 | Cancel an order only while `PENDING` | `POST /api/v1/orders/{orderId}/cancel` |

All six are implemented and tested. 138 tests, 0 failures.

---

## Quick start

```bash
./gradlew bootRun
```

The API is then on <http://localhost:8080>. Nothing else needs to be installed or started — the
database is embedded.

```bash
./gradlew test
```

**On Windows,** or if the build fails before it compiles anything, see
[Build environment notes](#build-environment-notes) — there are two machine-specific obstacles
that are not code problems.

### Interactive docs

| What | URL |
|------|-----|
| Swagger UI | <http://localhost:8080/swagger-ui.html> |
| OpenAPI 3.1 spec, JSON | <http://localhost:8080/v3/api-docs> |
| OpenAPI 3.1 spec, YAML | <http://localhost:8080/v3/api-docs.yaml> |

Every operation documents its failure responses, not just its happy path, so the `409` on cancelling
an order that has already moved on is visible without reading the source. Request bodies are
pre-filled from the schema examples, so "Try it out" works on create without typing anything.

### The database is in memory

H2 runs in memory and the schema is created at startup, so **restarting the application deletes
every order**. That is deliberate for an assignment — a reviewer needs no database to run this —
and it is the first thing that would change for real use. There is no seed data; create an order
and it exists until you stop the app.

---

## Endpoints

| Method | Path | Success | Failures |
|--------|------|---------|----------|
| `POST` | `/api/v1/orders` | `201` + `Location` | `400` validation, duplicate `productId`, malformed JSON |
| `GET` | `/api/v1/orders/{orderId}` | `200` | `404` unknown ID, `400` malformed UUID |
| `GET` | `/api/v1/orders` | `200` paged envelope | `400` bad `status`, `sort`, `page` or `size` |
| `POST` | `/api/v1/orders/{orderId}/cancel` | `200` cancelled order | `409` no longer `PENDING`, `404` unknown ID, `400` malformed UUID |

The list endpoint takes `?status=`, `?page=`, `?size=` and `?sort=property,direction`. It defaults
to 20 per page, newest first. An empty result is a `200` with `content: []`, never a `404`.

A create call, end to end:

```bash
curl -i -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{
        "customerId": "CUST-1001",
        "items": [
          {"productId":"SKU-MOUSE","productName":"Wireless Mouse","quantity":2,"unitPrice":25.00},
          {"productId":"SKU-PAD","productName":"Mouse Pad","quantity":1,"unitPrice":10.00}
        ]
      }'
```

[`docs/API_EXAMPLES.md`](./docs/API_EXAMPLES.md) has a runnable call for every endpoint and every
error case, with the responses a real server returned rather than invented ones. It also documents
a PowerShell trap: inline JSON loses its quotes before `curl.exe` sees it, so request bodies have
to be piped through stdin on that shell.

Two smoke scripts assert the status code of all 33 documented calls and exit non-zero on the first
mismatch:

```bash
bash docs/smoke-test.sh              # or: .\docs\smoke-test.ps1
```

They assert on `PENDING` orders, so start the app with `--orders.scheduler.enabled=false` first, or
the promotion job will move an order out from under a check.

---

## Order lifecycle

```
                    cancel API
             ┌───────────────────► CANCELLED (terminal)
             │
        PENDING
             │
             │ scheduler, every 60s
             ▼
        PROCESSING ──► SHIPPED ──► DELIVERED (terminal)
                       (no API in v1)
```

Exactly two transitions exist in v1:

- `PENDING` → `PROCESSING`, by the scheduler only
- `PENDING` → `CANCELLED`, by the cancel endpoint only

Everything else is refused: cancelling from `PROCESSING`, `SHIPPED`, `DELIVERED` or `CANCELLED`
returns `409` naming the status the order is actually in. There is no endpoint that lets a client
set a status directly, which is why cancel is modelled as a command — `POST .../cancel` — rather
than a `PATCH` on a `status` field. A general status patch would invite exactly the arbitrary
transitions the brief forbids.

`SHIPPED` and `DELIVERED` are modelled with no API on purpose. The brief automates one transition
and allows one client action, but the values have to exist for the enum and the status filter to be
complete.

### The background job

`PendingOrderPromotionJob` promotes every `PENDING` order to `PROCESSING` on a fixed 60-second rate,
starting immediately at boot so a restart picks up whatever the previous process left behind. It is
one `UPDATE` for the whole batch, not a loop over loaded entities.

```properties
orders.scheduler.promotion-rate-ms=60000   # shorten it to watch the job work
orders.scheduler.enabled=false             # switch it off entirely
```

`fixedRate` rather than `fixedDelay`, because the requirement is "every minute" regardless of what a
run costs. Spring's scheduler is single-threaded by default, so a slow run delays the next one
instead of overlapping with it. Exceptions are caught inside the run: an exception escaping a
`@Scheduled` method cancels all of its future executions, so one bad run would silently stop
promotion for the life of the process while the application still looked healthy.

---

## The one decision worth reading

Cancellation and the promotion job race by construction. The job promotes `PENDING` orders once a
minute; a cancel is only legal while an order is `PENDING`. Both can target the same row at the same
instant.

The obvious implementation reads, checks, then writes:

```java
Order order = repository.findById(id);        // reads PENDING
if (order.getStatus() != PENDING) throw ...;  // passes
order.setStatus(CANCELLED);                   // the job may have written PROCESSING by now
```

Between the read and the write, the job can promote the order — and this code will then overwrite
the promotion with a cancellation, or vice versa. Neither statement is wrong on its own; the
interleaving is.

Instead, the status is part of the `WHERE` clause, and the affected row count is the decision:

```sql
UPDATE orders SET status = 'CANCELLED', cancelled_at = ?, updated_at = ?, version = version + 1
 WHERE id = ? AND status = 'PENDING'
```

One row means the order was cancelled. Zero rows means it either does not exist or has already left
`PENDING` — the service re-reads to tell those two apart, because collapsing them would report a
live order as missing. The job's own statement carries the mirror-image predicate
(`WHERE status = 'PENDING'`), so an order that reached `CANCELLED` first no longer matches and can
never be resurrected. Whichever statement commits first wins, and the other matches nothing.

This is the behaviour the test suite spends most of its effort on. Both orderings — cancel-then-
promote and promote-then-cancel — are covered at the repository level, where the two statements are
issued directly, and end to end over HTTP.

---

## How it is put together

```
Controller   DTOs, status codes, OpenAPI annotations
    ▼
Service      business rules, totals, status guards, @Transactional
    ▼
Repository   Spring Data JPA
    ▼
H2
```

A layered modular monolith, which is the right shape for six endpoints. Entities never leave the
service layer: controllers speak in DTOs, so the JSON contract and the database schema can change
independently.

Some choices worth naming:

- **Totals are computed server-side.** `CreateOrderRequest` has no `totalAmount` and no `status`
  field, so a client cannot set a price or a state. `BigDecimal` throughout, never `double`.
- **Line items snapshot the product.** `productName` and `unitPrice` are copied onto the order, so
  renaming or repricing a product later does not silently rewrite historical orders.
- **A stable pagination envelope.** `PagedResponse` exists because Spring's `PageImpl` is not
  designed to be serialised directly and its JSON shape has changed between versions.
- **Sort properties are whitelisted.** An unvalidated property reaches Hibernate and comes back as a
  `500`; the whitelist turns that into a `400` that names the properties which do work.
- **One error shape everywhere,** produced by a single `@RestControllerAdvice`, so no failure ever
  returns a raw stack trace or Spring's default error body.
- **`@BatchSize` on the items collection,** so listing N orders does not issue N+1 queries. There is
  a test that counts the queries and fails if it regresses.

---

## Tests

138 tests, no failures. Run them with `./gradlew test`.

| Layer | What it proves |
|-------|----------------|
| Service unit tests | Totals, rounding, duplicate SKUs, cancel row-count branches, promote counts |
| Controller tests | Every status code and JSON shape, including all four failure modes |
| Repository slice tests | Cascade, orphan removal, paging, sorting, the conditional update, both race orderings |
| Integration tests | HTTP → H2 → HTTP for create, get, list, cancel and promotion |
| Scheduling test | That `@Scheduled` is really registered and really fires |
| Query-count test | The N+1 regression guard |
| OpenAPI test | That the generated spec still documents every operation and failure |

Two of these are deliberately awkward, and they are the ones I would point at first:

- **The race regression.** It is the evidence that the conditional-update design actually holds,
  rather than a claim in a document.
- **The scheduling test.** A missing `@EnableScheduling` is a classic silent failure — every unit
  test passes and nothing ever runs. That test persists a `PENDING` order, calls no service method,
  and polls until the scheduler moves it. It was verified to have teeth by commenting the annotation
  out and watching it fail.

The promotion job is switched **off** during the test suite. `@SpringBootTest` contexts are cached
and outlive the class that created them, so a live scheduler keeps ticking underneath later tests
and flips the `PENDING` orders they assert on. The one test that needs real wiring turns it back on
for itself and discards its context afterwards.

---

## Design decisions and trade-offs

**`CANCELLED` was added to the status enum.** The brief lists four statuses and also requires
cancellation, which those four cannot express. The alternatives were worse: a separate boolean would
put two sources of truth for one concept in the same row, and deleting the order would destroy
history and remove it from listings. `CANCELLED` is terminal.

**Cancel is a command, not a status patch.** `POST /{id}/cancel` rather than
`PATCH /{id} {"status": "..."}`. The patch shape reads as more RESTful but hands clients a general
transition mechanism, and requirement 6 allows exactly one transition.

**`409` and `404` are kept distinct.** Cancelling an already-`PROCESSING` order is a conflict, not a
missing resource. Collapsing them into one code would tell a caller their live order does not exist.

**H2 in memory, and `ddl-auto` creating the schema.** Right for a reviewer who should not have to
install anything; wrong for production, where this would be a real database with versioned
migrations. The JPA mapping carries no H2-specific behaviour.

**No authentication, no multi-tenancy, no payments or inventory.** Not requested, and adding them
would obscure the parts that were.

Deviations from the original design document are recorded, with reasons, in
[`docs/DESIGN.md`](./docs/DESIGN.md) §17 rather than quietly applied.

---

## Known limitations and next steps

In roughly the order I would tackle them:

1. **Create is not idempotent.** A retried checkout — a double-clicked button, a client timing out
   and retrying — produces two orders. An `Idempotency-Key` header, stored with a uniqueness
   constraint, is the standard fix and the most valuable thing missing.
2. **The scheduler runs on every instance.** Deployed to two pods, promotion fires twice a minute.
   The conditional `UPDATE` makes that harmless rather than corrupting, which is the point of the
   design, but the duplicate work should be removed with a lock (ShedLock) or by moving the job out
   of the API process.
3. **A real database with Flyway migrations,** replacing H2 and `ddl-auto`. Worth pairing with a
   Testcontainers integration test, so the JPA code is proven against the engine it will actually
   run on.
4. **No `SHIPPED` / `DELIVERED` API.** The states exist with no way to reach them, which is correct
   for the brief and incomplete as a product.
5. **No authentication or authorisation.** Any caller can read or cancel any order.
6. **`totalAmount` is stored as well as derived.** Fast to read and it preserves what the customer
   was actually charged, but it can in principle drift from the line items. A reconciliation check
   would close that.
7. **Errors are a custom shape, not RFC 7807 `ProblemDetail`.** The custom envelope is consistent
   and documented; the standard one would be more interoperable.

---

## Build environment notes

Two obstacles here are machine-specific rather than code problems, and neither is committed:

**`JAVA_HOME` is unset,** so `gradlew` exits with code `9009` until it points at a JDK.

**Avast intercepts HTTPS,** presenting a root CA that Windows trusts but the JVM's separate
truststore does not, so Gradle downloads fail with `PKIX path building failed` while browsers work
fine. Pointing the JVM at the Windows trust store works around it. Note that the Gradle *wrapper*
has its own JVM which does not read `org.gradle.jvmargs`, so when the failure trace mentions
`org.gradle.wrapper.Download`, the setting has to go through `GRADLE_OPTS` as well:

```powershell
$env:JAVA_HOME = "C:\Users\ACER\.jdks\graalvm-ce-25.0.2"
$env:GRADLE_OPTS = "-Djavax.net.ssl.trustStoreType=Windows-ROOT"
.\gradlew.bat test "-Dorg.gradle.jvmargs=-Djavax.net.ssl.trustStoreType=Windows-ROOT"
```

The durable fix is to disable Avast's HTTPS scanning or import its CA into the JDK.

---

## Documentation

| Document | What is in it |
|----------|---------------|
| [`docs/DESIGN.md`](./docs/DESIGN.md) | Architecture, API design, schema, validation rules, edge cases, test strategy, and every deviation from the plan with its reason |
| [`docs/API_EXAMPLES.md`](./docs/API_EXAMPLES.md) | A runnable call for every endpoint and error case, with real responses |
| [`docs/SPRINT_PLAN.md`](./docs/SPRINT_PLAN.md) | How the work was sliced, with acceptance criteria |
| [`docs/SPRINT_STATUS.md`](./docs/SPRINT_STATUS.md) | What is delivered, sprint by sprint, and what is still open |
