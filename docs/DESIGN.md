# Ecommerce Order Processing System — Design Document

Design proposal for the take-home assignment. **No implementation code is included.**

**Stack (decided):** Java 17, Spring Boot 4.1.1, Spring Web MVC, Spring Data **JPA** (Hibernate), **H2** in-memory database, Bean Validation, Lombok, springdoc-openapi (Swagger UI), JUnit 5.

**Decisions taken:** relational schema via JPA + H2; `CANCELLED` added to the status enum; scope extras = OpenAPI/Swagger UI, pagination + sorting on list, and a README.

---

## 1. Requirement analysis

| # | Requirement | Interpretation |
|---|-------------|----------------|
| 1 | Create an order with multiple items | One `POST`. At least one line item required. Totals computed server-side. |
| 2 | Retrieve order by order ID | Single-resource `GET`. Unknown ID → `404`. |
| 3 | Statuses `PENDING`, `PROCESSING`, `SHIPPED`, `DELIVERED` | Lifecycle statuses. Cancellation needs a fifth value. |
| 4 | Background job `PENDING` → `PROCESSING` every minute | Scheduled task, not an API. Must not fight the cancel operation. |
| 5 | List all orders, optional status filter | Collection `GET`, now paginated. Invalid status → `400`. |
| 6 | Cancel only when `PENDING` | Command endpoint. Any other status → `409`. |

### 1.1 Decisions on unspecified points

| Topic | Decision | Reason |
|-------|----------|--------|
| Cancelled representation | Add `CANCELLED` to `OrderStatus` | The four given statuses cannot express a cancelled order. Deleting the row would destroy history and break listing. |
| `SHIPPED` / `DELIVERED` transitions | Present in the model, **no public API in v1** | The brief automates only one transition and allows only cancel. The values must still exist so the enum and status filter are complete. |
| Customer / product master data | Not modelled as tables | Out of scope. Store `customerId` and a **product snapshot** per line. |
| Payments, inventory, carriers | Out of scope | Not requested. |
| Auth / multi-tenancy | Out of scope | Not requested; called out in the README. |
| Idempotent create | Not in v1 | No `Idempotency-Key` header. Noted as a future improvement. |
| Pagination | **In scope** | Requested extra. Default 20 per page, newest first. |

### 1.2 Status machine

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

Transitions allowed in v1:

- `PENDING` → `PROCESSING` — scheduler only
- `PENDING` → `CANCELLED` — cancel API only

Rejected in v1: cancel from `PROCESSING` / `SHIPPED` / `DELIVERED` / `CANCELLED`, any client-driven status change, and any backwards move.

---

## 2. Architecture

A single **modular monolith** with a strict one-directional layer flow. No microservices, event bus, or CQRS — the assignment is CRUD plus one guarded transition plus one job.

```
HTTP
  ▼
Controller     DTOs in/out, HTTP status codes, OpenAPI annotations
  ▼
Service        business rules, totals, status guards, @Transactional
  ▼
Repository     Spring Data JPA
  ▼
H2             orders + order_items
```

The scheduler does **not** contain rules. It calls the same service method an API would:

```
@Scheduled job  →  OrderService.promotePendingOrders()  →  Repository (bulk UPDATE)
```

### 2.1 Layer rules

| Layer | May depend on | Must not depend on |
|-------|---------------|--------------------|
| Controller | Service, DTOs | Entities, repositories |
| Service | Repository, entities, DTOs | HTTP types, servlet API |
| Repository | Entities | Controllers, DTOs |
| Scheduler | Service | Repositories directly, duplicated status logic |

Entities are never returned from a controller. Exposing a JPA entity leaks the schema and risks lazy-loading serialization failures on `items`.

---

## 3. API design

Base path `/api/v1/orders`, media type `application/json`.

| Method | Path | Purpose | Success |
|--------|------|---------|---------|
| `POST` | `/api/v1/orders` | Create order | `201` + body + `Location` |
| `GET` | `/api/v1/orders/{orderId}` | Get one order | `200` + body |
| `GET` | `/api/v1/orders` | List orders, paginated | `200` + page envelope |
| `GET` | `/api/v1/orders?status=PENDING&page=0&size=20&sort=createdAt,desc` | Filter + paginate | `200` + page envelope |
| `POST` | `/api/v1/orders/{orderId}/cancel` | Cancel if `PENDING` | `200` + updated body |

Cancel is a `POST` command rather than a `PATCH` on `status`. A generic status `PATCH` would invite arbitrary client-supplied transitions, which requirement 6 forbids.

### 3.1 Create — request

```json
{
  "customerId": "CUST-1001",
  "items": [
    { "productId": "SKU-MOUSE", "productName": "Wireless Mouse", "quantity": 2, "unitPrice": 25.00 },
    { "productId": "SKU-PAD",   "productName": "Mouse Pad",      "quantity": 1, "unitPrice": 10.00 }
  ]
}
```

Server assigns `id`, `status = PENDING`, `lineTotal` per item, `totalAmount`, `createdAt`, `updatedAt`. The request DTO has **no** `status` or `totalAmount` field, so those cannot be injected by a client.

### 3.2 Order — response

Used by create, get, and cancel.

```json
{
  "id": "8f14e45f-ea16-4c2b-9c1a-3d0f7b5a91cc",
  "customerId": "CUST-1001",
  "status": "PENDING",
  "totalAmount": 60.00,
  "items": [
    { "productId": "SKU-MOUSE", "productName": "Wireless Mouse", "quantity": 2, "unitPrice": 25.00, "lineTotal": 50.00 },
    { "productId": "SKU-PAD",   "productName": "Mouse Pad",      "quantity": 1, "unitPrice": 10.00, "lineTotal": 10.00 }
  ],
  "createdAt": "2026-09-03T14:30:00Z",
  "updatedAt": "2026-09-03T14:30:00Z",
  "cancelledAt": null
}
```

### 3.3 List — paginated response

A custom envelope is returned instead of serializing Spring's `Page` directly. Serialized `PageImpl` is unstable across versions and Spring logs a warning about it.

```json
{
  "content": [ /* order objects, newest first */ ],
  "page": 0,
  "size": 20,
  "totalElements": 42,
  "totalPages": 3,
  "first": true,
  "last": false,
  "sort": "createdAt: DESC"
}
```

Empty result → `200` with `content: []` and `totalElements: 0`.

### 3.4 List query parameters

| Param | Type | Default | Rules |
|-------|------|---------|-------|
| `status` | `OrderStatus` | none (all) | Must be a valid enum name, uppercase. Invalid → `400`. |
| `page` | int | `0` | `>= 0`. Negative → `400`. |
| `size` | int | `20` | `1..100`. Above max is clamped to 100 by `spring.data.web.pageable.max-page-size`. |
| `sort` | string | `createdAt,desc` | **Whitelist** `createdAt`, `updatedAt`, `totalAmount`, `status`. Anything else → `400`. |

The sort whitelist matters: passing an unknown property to `Pageable` produces a Hibernate `PropertyReferenceException` surfacing as a `500`. Validating against a known set turns that into a clean `400`.

### 3.5 Error body

Single shape for every failure.

```json
{
  "timestamp": "2026-09-03T14:31:00Z",
  "status": 409,
  "error": "Conflict",
  "message": "Order can be cancelled only while PENDING. Current status: PROCESSING",
  "path": "/api/v1/orders/8f14e45f-ea16-4c2b-9c1a-3d0f7b5a91cc/cancel",
  "details": []
}
```

`details` carries per-field validation messages, e.g. `"items[0].quantity: must be at least 1"`.

### 3.6 Status code map

| Situation | Code |
|-----------|------|
| Order created | `201` |
| Fetched / listed / cancelled | `200` |
| Validation failure, bad enum, bad paging/sort, malformed JSON | `400` |
| Unknown order ID | `404` |
| Cancel when not `PENDING` | `409` |
| Unhandled error | `500` |

---

## 4. Entities

Two entities in one aggregate. `Order` is the aggregate root and the only entity a repository is exposed for.

### 4.1 `OrderStatus` (enum)

`PENDING`, `PROCESSING`, `SHIPPED`, `DELIVERED`, `CANCELLED`

Persisted with `@Enumerated(EnumType.STRING)`. Ordinal storage is unsafe: inserting a value later silently reinterprets existing rows.

### 4.2 `Order` (table `orders`)

| Field | Type | Mapping notes |
|-------|------|---------------|
| `id` | `UUID` | `@Id`, generated. Non-enumerable public identifier. |
| `customerId` | `String` | `not null`, length 64 |
| `items` | `List<OrderItem>` | `@OneToMany(mappedBy = "order", cascade = ALL, orphanRemoval = true)` |
| `status` | `OrderStatus` | `not null`, string enum, indexed |
| `totalAmount` | `BigDecimal` | `precision = 12, scale = 2`, server-computed |
| `createdAt` | `Instant` | set once, `not null` |
| `updatedAt` | `Instant` | updated on every state change |
| `cancelledAt` | `Instant` | nullable, set only on successful cancel |
| `version` | `Long` | `@Version`, optimistic locking (see §5.4) |

A sequential `Long` identity key is a valid alternative and slightly faster to index, but it leaks order volume and lets clients probe other customers' orders by incrementing. `UUID` avoids both.

### 4.3 `OrderItem` (table `order_items`)

| Field | Type | Mapping notes |
|-------|------|---------------|
| `id` | `Long` | `@Id`, identity. Internal only, not exposed in the API. |
| `order` | `Order` | `@ManyToOne(fetch = LAZY)`, `@JoinColumn(name = "order_id")`, `not null` |
| `productId` | `String` | `not null` — snapshot, no FK to a product table |
| `productName` | `String` | `not null` — snapshot so later catalog renames don't rewrite history |
| `quantity` | `Integer` | `not null`, `> 0` |
| `unitPrice` | `BigDecimal` | `precision = 12, scale = 2`, `>= 0` |
| `lineTotal` | `BigDecimal` | `quantity * unitPrice`, stored |

`lineTotal` and `totalAmount` are stored rather than computed on read so a historical order keeps the money it was actually placed with, and so `totalAmount` remains sortable in SQL.

### 4.4 Fetching

`items` is `LAZY` on the `@ManyToOne` side. For `Order.items`, use an explicit fetch join (`@EntityGraph` or `join fetch`) on the read paths. Listing N orders and lazily touching each order's items is the N+1 query problem; the list endpoint must fetch-join or batch.

One caution: a fetch join across a `@OneToMany` **plus** `Pageable` makes Hibernate paginate in memory (it warns about applying pagination to a collection fetch). For the list endpoint, page the order IDs first, then load items for that page — or accept `@BatchSize` on the collection, which is simpler and adequate at assignment scale.

---

## 5. Database schema

**Engine:** H2 in-memory, MySQL/PostgreSQL-compatible SQL. Two tables, one foreign key.

### 5.1 DDL

```sql
CREATE TABLE orders (
    id             UUID          NOT NULL,
    customer_id    VARCHAR(64)   NOT NULL,
    status         VARCHAR(20)   NOT NULL,
    total_amount   DECIMAL(12,2) NOT NULL,
    created_at     TIMESTAMP(6)  NOT NULL,
    updated_at     TIMESTAMP(6)  NOT NULL,
    cancelled_at   TIMESTAMP(6)  NULL,
    version        BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT ck_orders_status CHECK (status IN
        ('PENDING','PROCESSING','SHIPPED','DELIVERED','CANCELLED')),
    CONSTRAINT ck_orders_total CHECK (total_amount >= 0)
);

CREATE TABLE order_items (
    id            BIGINT        GENERATED BY DEFAULT AS IDENTITY,
    order_id      UUID          NOT NULL,
    product_id    VARCHAR(64)   NOT NULL,
    product_name  VARCHAR(255)  NOT NULL,
    quantity      INTEGER       NOT NULL,
    unit_price    DECIMAL(12,2) NOT NULL,
    line_total    DECIMAL(12,2) NOT NULL,
    CONSTRAINT pk_order_items PRIMARY KEY (id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id)
        REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT ck_order_items_qty CHECK (quantity > 0),
    CONSTRAINT ck_order_items_price CHECK (unit_price >= 0)
);

CREATE INDEX idx_orders_status            ON orders (status);
CREATE INDEX idx_orders_created_at        ON orders (created_at DESC);
CREATE INDEX idx_orders_status_created_at ON orders (status, created_at DESC);
CREATE INDEX idx_order_items_order_id     ON order_items (order_id);
```

### 5.2 Index rationale

| Index | Serves |
|-------|--------|
| `idx_orders_status` | Scheduler's `WHERE status = 'PENDING'` |
| `idx_orders_created_at` | Default list ordering, newest first |
| `idx_orders_status_created_at` | The common combined path: filter by status, order by date, paginate |
| `idx_order_items_order_id` | Loading an order's lines and the FK check |

The compound index makes the two single-column ones partly redundant. Keeping `status` alone is still worthwhile for the job's count/update. At assignment scale this is a documented choice, not a measured one.

### 5.3 Money and time

- **Money:** `DECIMAL(12,2)` ↔ `BigDecimal`. Never `double`/`FLOAT` — binary floating point cannot represent `0.10` exactly and totals drift.
- **Multiplication:** `unitPrice.multiply(quantity)` then `setScale(2, HALF_UP)`.
- **Time:** `Instant`, UTC, `TIMESTAMP(6)`. No local time zones stored.

### 5.4 Concurrency: cancel versus the scheduler

Both target `PENDING` rows. A naive read-modify-write loses updates:

1. Job loads order, sees `PENDING`
2. Cancel commits `CANCELLED`
3. Job commits `PROCESSING` → **a cancelled order is silently resurrected**

**Rule: every status change is a status-conditioned SQL update, never a load-then-save.**

Scheduler — one atomic statement for the whole batch:

```sql
UPDATE orders
   SET status = 'PROCESSING', updated_at = :now, version = version + 1
 WHERE status = 'PENDING';
```

Cancel — conditioned on the current status, using the affected row count as the decision:

```sql
UPDATE orders
   SET status = 'CANCELLED', cancelled_at = :now, updated_at = :now, version = version + 1
 WHERE id = :id AND status = 'PENDING';
```

Interpreting the returned row count on cancel:

| Rows updated | Meaning | Response |
|--------------|---------|----------|
| `1` | Cancelled | `200` |
| `0`, and the order does not exist | Unknown ID | `404` |
| `0`, and the order exists with another status | Lost the race, or already advanced | `409` |

So cancel is: attempt the conditional update; if it updates 0 rows, re-read the row to distinguish `404` from `409`. The scheduler can never overwrite `CANCELLED` because its `WHERE` clause excludes it.

Two JPA specifics that bite here:

- Bulk `@Modifying` JPQL bypasses the persistence context. Use `@Modifying(clearAutomatically = true, flushAutomatically = true)` or stale entities linger in the same transaction.
- Bulk updates also bypass `@Version` checking and entity callbacks, which is why `version` and `updated_at` are incremented explicitly in the statement.

`@Version` remains on the entity for ordinary entity-level writes; the conditional `WHERE status = ...` predicate is the primary guard.

### 5.5 Transactions

| Operation | Boundary |
|-----------|----------|
| Create | `@Transactional` — order and all items commit together or not at all |
| Get / list | `@Transactional(readOnly = true)` |
| Cancel | `@Transactional` |
| Promote pending | `@Transactional` around the single bulk update |

A partially saved order (header without lines) must be impossible.

---

## 6. Package structure

```
com.vikaan.ordermanagementsystem
├── OrderManagementSystemApplication      // @SpringBootApplication, @EnableScheduling
├── config
│   ├── OpenApiConfig                     // API metadata bean
│   └── SchedulingConfig                  // optional: named task scheduler
├── controller
│   └── OrderController
├── dto
│   ├── request
│   │   ├── CreateOrderRequest
│   │   └── OrderItemRequest
│   └── response
│       ├── OrderResponse
│       ├── OrderItemResponse
│       ├── PagedResponse<T>
│       └── ErrorResponse
├── entity
│   ├── Order
│   ├── OrderItem
│   └── OrderStatus
├── exception
│   ├── OrderNotFoundException
│   ├── InvalidOrderStateException
│   ├── InvalidRequestException
│   └── GlobalExceptionHandler            // @RestControllerAdvice
├── mapper
│   └── OrderMapper                       // entity ↔ DTO, plain static methods
├── repository
│   └── OrderRepository
├── scheduler
│   └── PendingOrderPromotionJob
└── service
    ├── OrderService                      // interface
    └── OrderServiceImpl
```

`src/test/java` mirrors these packages.

---

## 7. Validations

### 7.1 Create request (Bean Validation)

| Field | Constraints |
|-------|-------------|
| `customerId` | `@NotBlank`, `@Size(max = 64)` |
| `items` | `@NotEmpty`, `@Valid` (so nested items are validated) |
| `items[].productId` | `@NotBlank`, `@Size(max = 64)` |
| `items[].productName` | `@NotBlank`, `@Size(max = 255)` |
| `items[].quantity` | `@NotNull`, `@Min(1)`, `@Max(1000)` |
| `items[].unitPrice` | `@NotNull`, `@DecimalMin("0.00")`, `@Digits(integer = 10, fraction = 2)` |

`@Valid` on the collection is required — without it nested item constraints are silently skipped and invalid lines persist.

Any failure rejects the **whole** request with `400`. No partial order is written.

**Duplicate SKUs:** reject with `400` when the same `productId` appears twice in one request. The alternative (merge quantities) is also defensible but hides a probable client bug; rejecting is explicit and easier to assert in a test.

### 7.2 Path and query validation

| Input | Rule |
|-------|------|
| `{orderId}` | Must parse as a UUID. Malformed → `400`; well-formed but absent → `404`. |
| `status` | Valid `OrderStatus` name. Invalid → `400` listing the accepted values. |
| `page` / `size` | `page >= 0`, `size` in `1..100`. |
| `sort` | Property must be in the whitelist (§3.4). |

### 7.3 Business rules enforced in the service

These are invariants, not annotations, and belong in the service where they cannot be bypassed:

- A new order is always `PENDING`.
- `lineTotal` and `totalAmount` are always recomputed server-side and never read from the request.
- Cancel is permitted only from `PENDING`, enforced by the SQL predicate rather than an in-memory `if`.
- The scheduler touches only `PENDING` rows.
- No endpoint accepts a client-supplied status.

---

## 8. Edge cases

| Case | Expected behaviour |
|------|--------------------|
| `items` empty or missing | `400` |
| `quantity` zero or negative | `400` |
| `unitPrice` negative | `400` |
| `unitPrice` with 3+ decimals | `400` (`@Digits`) |
| Missing / blank `customerId` | `400` |
| Duplicate `productId` in one request | `400` |
| Malformed JSON body | `400`, not `500` |
| Very large `quantity` causing total overflow | Bounded by `@Max` and `DECIMAL(12,2)` |
| Get / cancel unknown UUID | `404` |
| Get / cancel malformed UUID | `400` |
| Cancel a `PROCESSING`, `SHIPPED`, `DELIVERED` order | `409` |
| Cancel an already `CANCELLED` order | `409`, not a silent success |
| Cancel and the job hit the same order simultaneously | Exactly one wins; the conditional update decides (§5.4) |
| Job runs with no `PENDING` rows | No-op, no error, logs `0 promoted` |
| Job overruns its own 60s interval | Single-threaded scheduler serializes runs; conditional update makes a late run harmless |
| App restarts mid-job | Safe — the next tick promotes whatever is still `PENDING` |
| `page` beyond the last page | `200` with empty `content`, correct `totalElements` |
| `size` above the maximum | Clamped to 100 |
| `sort` on an unknown property | `400`, not a `500` from Hibernate |
| List with no filter | All orders, newest first |
| `?status=pending` (lowercase) or `?status=FOO` | `400` |
| Client sends `status` / `totalAmount` on create | Not bound; ignored |
| H2 in-memory restart | All data is lost by design; stated in the README |

---

## 9. Background job design

| Aspect | Choice |
|--------|--------|
| Mechanism | `@EnableScheduling` + `@Scheduled` |
| Cadence | Every 60s — `fixedRateString` bound to a property, or cron `0 * * * * *` |
| Configurable | `orders.scheduler.promotion-rate-ms` so tests can shorten it |
| Action | Bulk `UPDATE ... WHERE status = 'PENDING'` |
| Atomicity | One SQL statement inside one transaction; no per-row loop |
| Logging | Log the promoted count per tick; skip noisy logs when `0` |
| Failure | Exceptions are caught and logged so a failed tick does not kill the scheduler thread |
| Overlap | Spring's default single-thread scheduler prevents concurrent runs |
| Testability | The rule lives in `OrderService.promotePendingOrders()`, callable directly |

`fixedRate` (not `fixedDelay`) matches "every minute". No HTTP endpoint triggers the job; tests call the service method.

---

## 10. Cross-cutting concerns

| Concern | Approach |
|---------|----------|
| Error handling | `@RestControllerAdvice` mapping `OrderNotFoundException` → `404`, `InvalidOrderStateException` → `409`, `MethodArgumentNotValidException` / `HttpMessageNotReadableException` / `MethodArgumentTypeMismatchException` → `400`, fallback → `500` |
| Never leak internals | No stack traces or SQL in responses; log server-side with a correlation value |
| Time | `Instant`, UTC everywhere; an injected `Clock` makes timestamps testable |
| Money | `BigDecimal`, scale 2, `HALF_UP` |
| Logging | Info on create / cancel / promotion count; warn on rejected cancel |
| Config profiles | `default` (H2), `test` (H2 + fast scheduler) |
| Security / actuator | Out of scope, documented as such |

---

## 11. API documentation (Swagger / OpenAPI)

| Item | Choice |
|------|--------|
| Library | `org.springdoc:springdoc-openapi-starter-webmvc-ui` |
| Version | **3.1.0** — the `3.x` line targets Spring Boot 4.x. `2.8.x` supports Boot 3 only and will not auto-configure here. |
| Swagger UI | `/swagger-ui.html` |
| Raw spec | `/v3/api-docs` |
| Required properties | Boot 4 needs these set explicitly: `springdoc.api-docs.enabled=true`, `springdoc.swagger-ui.enabled=true` |
| Metadata | An `OpenAPI` bean in `OpenApiConfig` with title, version, description |
| Annotations | `@Tag` on the controller; `@Operation` plus `@ApiResponses` per endpoint documenting `400` / `404` / `409`; `@Schema` on DTO fields with examples |

Documenting the failure responses matters as much as the success ones — the `409` cancel rule is the most interesting part of this API.

---

## 12. Build dependency changes

The current `build.gradle` is wired for MongoDB and must change. Documented here, **not yet applied**.

**Remove**

- `org.springframework.boot:spring-boot-starter-data-mongodb`
- `org.springframework.boot:spring-boot-starter-data-mongodb-test`

**Add**

- `org.springframework.boot:spring-boot-starter-data-jpa`
- `org.springframework.boot:spring-boot-starter-data-jpa-test` *(test)*
- `com.h2database:h2` *(runtimeOnly, plus test)*
- `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0`

**Keep**

- `spring-boot-starter-webmvc`, `spring-boot-starter-validation`, Lombok, `spring-boot-starter-webmvc-test`, `spring-boot-starter-validation-test`, `junit-platform-launcher`

Boot 4 notes that affect this build:

- Boot 4 split the old fat starters into modular ones. `spring-boot-starter-web` is now `spring-boot-starter-webmvc` (already correct in this project), and features that used to arrive transitively need their own starter.
- The H2 **console** is no longer auto-configured from the bare `h2` dependency; it needs its own module. The console is optional here — JPA against in-memory H2 works without it — so it is listed as optional and its exact artifact name should be confirmed against the Boot 4.1 dependency list if wanted.
- Test starters follow the `*-test` naming (`spring-boot-starter-data-jpa-test`).

### 12.1 Key properties

```properties
spring.datasource.url=jdbc:h2:mem:orderdb;DB_CLOSE_DELAY=-1
spring.jpa.hibernate.ddl-auto=update
spring.jpa.open-in-view=false
spring.data.web.pageable.max-page-size=100
spring.data.web.pageable.default-page-size=20
springdoc.api-docs.enabled=true
springdoc.swagger-ui.enabled=true
orders.scheduler.promotion-rate-ms=60000
```

`spring.jpa.open-in-view=false` is deliberate. It is on by default and hides N+1 lazy loads behind a request-scoped session, which then explode in tests or async code. Turning it off forces the fetch strategy to be explicit (§4.4).

`ddl-auto=update` is acceptable for an in-memory take-home. Flyway with the DDL from §5.1 would be the production answer and can be mentioned in the README as the next step.

---

## 13. Test strategy

Prove the six features and the status rules, especially the cancel-versus-job race. Fast unit tests for rules; slice tests for HTTP; integration tests for persistence.

### 13.1 Service unit tests (mocked repository)

| Test | Assertion |
|------|-----------|
| Create with multiple items | Status `PENDING`, `lineTotal` per item, `totalAmount` = sum, timestamps set |
| Create computes totals, ignores client input | Totals derive only from quantity × price |
| Create with duplicate SKUs | Rejected, nothing saved |
| Get existing / missing | Returns mapped order / throws `OrderNotFoundException` |
| List all and by status | Correct repository method and `Pageable` passed through |
| Cancel `PENDING` | Becomes `CANCELLED`, `cancelledAt` set |
| Cancel when conditional update returns 0 and row exists | `InvalidOrderStateException` |
| Cancel when conditional update returns 0 and row is absent | `OrderNotFoundException` |
| Promote pending | Delegates to the bulk update, returns the count |
| Promote with none pending | Returns 0, no exception |

`BigDecimal` assertions use `compareTo` or `isEqualByComparingTo`. `assertEquals(new BigDecimal("60.00"), total)` fails against `60.0` because `equals` compares scale.

### 13.2 Controller tests (`@WebMvcTest`, mocked service)

| Test | Assertion |
|------|-----------|
| `POST` valid | `201`, `Location` header, JSON shape |
| `POST` empty items / bad quantity / negative price / blank customer | `400` with field details |
| `POST` malformed JSON | `400` |
| `GET` by id | `200`, correct body |
| `GET` unknown id | `404` |
| `GET` malformed uuid | `400` |
| `GET` list | `200`, page envelope fields present |
| `GET` list with `status` | Filter reaches the service |
| `GET` list with invalid `status` / `sort` / negative `page` | `400` |
| `POST` cancel | `200`, `CANCELLED` |
| `POST` cancel conflict | `409` |

### 13.3 Repository tests (`@DataJpaTest` on H2)

This layer holds the concurrency guard, so it needs direct tests:

| Test | Assertion |
|------|-----------|
| Conditional cancel update on a `PENDING` row | Returns 1, row is `CANCELLED` |
| Conditional cancel update on a `PROCESSING` row | Returns **0**, row unchanged |
| Bulk promote | Only `PENDING` rows become `PROCESSING`; `CANCELLED` / `SHIPPED` untouched |
| Find by status with paging and sort | Correct page content and order |
| Cascade persist | Saving an order writes its `order_items` |
| `orphanRemoval` / cascade delete | Deleting an order removes its items |

### 13.4 Integration tests (`@SpringBootTest` + `MockMvc` or `TestRestTemplate`)

| Test | Assertion |
|------|-----------|
| Create then get | Full round-trip through H2, items intact |
| Create several, list page 0 size 2 | Pagination metadata and slicing correct |
| Filter by status end to end | Only matching orders |
| Cancel then get | `CANCELLED` persisted, `cancelledAt` set |
| Promote then attempt cancel | `409`; order stays `PROCESSING` |
| Promote then list `?status=PROCESSING` | Job effect visible through the API |
| Cancel then promote | Order stays `CANCELLED` — the regression that proves §5.4 |

Reset state between tests with `@Transactional` rollback or an explicit cleanup in `@BeforeEach`; a shared in-memory database otherwise leaks rows between tests and breaks pagination counts.

### 13.5 Scheduler tests

- Never `Thread.sleep(60000)`. Call `promotePendingOrders()` directly.
- Optionally assert the wiring with a short `orders.scheduler.promotion-rate-ms` in the `test` profile and an awaited condition.
- Assert `@Scheduled` is actually enabled (a missing `@EnableScheduling` is a classic silent failure — every unit test passes and nothing ever runs).

### 13.6 Not worth testing

Hibernate internals, Lombok accessors, Spring's scheduling framework, springdoc's generated page.

### 13.7 Suggested size

Roughly **25–30** tests across the four layers. Enough to cover every status code and the race condition without becoming a maintenance burden for a take-home.

---

## 14. README deliverable

The README (separate from this document) should carry:

1. One-paragraph overview and the tech stack
2. How to run — `./gradlew bootRun` — and how to run tests — `./gradlew test`
3. Swagger UI link (`/swagger-ui.html`) and H2 note (in-memory, data lost on restart)
4. Endpoint table with sample `curl` calls for create, get, list, filter, paginate, cancel — maintained in [`API_EXAMPLES.md`](./API_EXAMPLES.md), which the README can link to rather than duplicate
5. The status machine diagram and the cancel rule
6. Design decisions and trade-offs, including `CANCELLED` as an addition to the brief
7. Known limitations and next steps — auth, Flyway, real database, idempotent create, `SHIPPED`/`DELIVERED` APIs

---

## 15. Implementation order

Sequenced into five deliverable slices with stories, estimates and acceptance criteria in [`SPRINT_PLAN.md`](./SPRINT_PLAN.md). The raw order is:

1. Swap the build to JPA + H2; add springdoc
2. Entities, enum, repository
3. Service: create, get, list
4. Controller, DTOs, exception handler, validation
5. Pagination envelope and sort whitelist
6. Cancel with the conditional update
7. Scheduler with the bulk promote
8. Swagger annotations and `OpenApiConfig`
9. Tests, layer by layer
10. README

Nothing outside this design gets added — no payments, inventory, messaging, or caching.

---

## 16. Summary

One Spring Boot application, two tables, five endpoints, one scheduled job.

The choices worth defending in a review:

- **`CANCELLED` added** to the status enum, because the four listed statuses cannot represent a cancelled order and deleting the row would lose history.
- **Status changes use status-conditioned SQL updates**, so the every-minute job and a concurrent cancel cannot corrupt each other, and the affected row count cleanly separates `404` from `409`.
- **Money is server-computed `BigDecimal`/`DECIMAL(12,2)`**, never client-supplied and never floating point.
- **Line items are a cascaded child table with product-name snapshots**, so historical orders stay accurate when the catalog changes.
- **Entities never cross the HTTP boundary**, and `open-in-view` is disabled so fetching is explicit rather than accidental.

---

## 17. Implementation notes and deviations

Recorded as Sprint 1 was built, per the Definition of Done in [`SPRINT_PLAN.md`](./SPRINT_PLAN.md) §8. Nothing here changes the architecture; these are the points where reality differed from the plan written before any code existed.

### 17.1 Deviations from this document

| # | Deviation | Reason |
|---|-----------|--------|
| 1 | **Java toolchain moved from 17 to 25** | No JDK 17 exists on the build machine; the available JDKs are 21 and two 25.x. Gradle toolchains fail rather than silently substituting, so the build could not run at 17. Java 25 is the current LTS and the baseline Boot 4 is designed around. Boot 4's floor is 17, so lowering it again only requires installing a JDK 17. |
| 2 | **DTOs are records**, not Lombok classes | Immutability comes free, and a record cannot be given a `status` or `totalAmount` setter by accident. Lombok is still used for the JPA entities, which need a no-arg constructor and mutability. |
| 3 | **`config/ClockConfig` added** to the §6 package structure | §10 calls for an injected `Clock` for testable timestamps, but §6 never listed a home for the bean. |
| 4 | **Timestamps truncated to microseconds** on create | Found while running the demo script: the create response returned nanosecond precision (`…088344900Z`) while a subsequent `GET` of the same order returned `…088345Z`, because `TIMESTAMP(6)` rounds to microseconds. The same order reported two different `createdAt` values depending on the endpoint. Truncating at the service boundary makes the response equal to the stored value. |
| 5 | **Indexes (§5.2) not created yet** | Deferred to Sprint 2 task 2.7, where the list and filter queries that justify them are built. `ddl-auto=update` will add them then. |
| 6 | **§12.1 properties added incrementally** | Only the datasource, `ddl-auto` and `open-in-view` settings are in place. The pageable, springdoc and scheduler properties arrive with the sprints that use them. |

### 17.2 Spring Boot 4 findings that affect later sprints

Boot 4's modularization moved things that most tutorials still show at their Boot 3 locations. Discovered by compiler error, then confirmed against the jars:

| Concern | Boot 3 (most documentation) | **Boot 4.1.1 (this project)** |
|---------|------------------------------|-------------------------------|
| Web slice test | `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest` | `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` |
| JPA slice test | `org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest` | `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest` |
| Test entity manager | `…test.autoconfigure.orm.jpa.TestEntityManager` | `org.springframework.boot.jpa.test.autoconfigure.TestEntityManager` |
| Mock a bean | `@MockBean` | `@MockitoBean` (`org.springframework.test.context.bean.override.mockito`) |
| JSON | Jackson 2 (`com.fasterxml.jackson`) | **Jackson 3** (`tools.jackson`, 3.1.5) |

The Jackson 3 move matters twice over: `java.time` support is built in, so no JSR-310 module is needed and `Instant` already serializes as ISO-8601; and any future Jackson annotation or `ObjectMapper` injection must use `tools.jackson`, not `com.fasterxml.jackson`.

Confirmed versions on the classpath: Hibernate ORM 7.4.5, H2 2.4.240, JUnit Jupiter 6.0.3, Mockito 5.23.0, AssertJ 3.27.7.

The service unit tests construct mocks with `Mockito.mock(...)` in `@BeforeEach` instead of `@ExtendWith(MockitoExtension.class)`, avoiding any dependence on the Mockito-to-JUnit-6 extension bridge.

### 17.3 Local build environment

Two machine-specific obstacles, neither of which is a code problem and neither of which is committed to the repository:

1. **Avast is intercepting HTTPS.** It presents its own root CA (`CN=Avast Web/Mail Shield Root`), which Windows trusts but the JVM's separate truststore does not. Every Gradle download therefore fails with `PKIX path building failed` while browsers and PowerShell work. Workaround used: `-Dorg.gradle.jvmargs=-Djavax.net.ssl.trustStoreType=Windows-ROOT`, which points the JVM at the Windows trust store. The durable fix is to disable Avast's HTTPS scanning or import its CA into the JDK.

   That flag configures the *daemon*. It does not reach the wrapper's own bootstrap JVM, which is what downloads the distribution and re-validates `distributionUrl`. When the failure trace shows `org.gradle.wrapper.Download`, the trust store has to be exported to the wrapper as well — `$env:GRADLE_OPTS = "-Djavax.net.ssl.trustStoreType=Windows-ROOT"`. This surfaced in Sprint 5, on the first build that needed to fetch a new dependency.
2. **`JAVA_HOME` is unset and `java` is not on `PATH`**, so `gradlew` exits with `9009` until `JAVA_HOME` is set to a JDK.

The build command that works here:

```powershell
$env:JAVA_HOME = "C:\Users\ACER\.jdks\graalvm-ce-25.0.2"
.\gradlew.bat test "-Dorg.gradle.jvmargs=-Djavax.net.ssl.trustStoreType=Windows-ROOT"
```

### 17.4 Sprint 1 status

Delivered: requirement 1 (create with multiple items) and requirement 2 (retrieve by ID), plus the full five-value status enum.

**23 tests, 0 failures, 0 skipped** — 8 service unit, 10 controller (`@WebMvcTest`), 4 repository (`@DataJpaTest`), 1 context load.

Verified against a running app: `201` with `Location` and server-computed `totalAmount` of `60.00`; `200` on re-read; `404` for an unknown UUID; and `400` for empty items, a blank `customerId`, a zero quantity, a malformed UUID, and a duplicate `productId`. Reproducible via [`API_EXAMPLES.md`](./API_EXAMPLES.md) and the two smoke-test scripts alongside it.

### 17.5 Known test gaps

Accepted deliberately at the end of Sprint 1. In each case the constraint **is** implemented and enforced; what is missing is an automated test proving it. This is a conscious exception to §8 of the sprint plan's Definition of Done, not an oversight.

| Documented edge case (§8) | Enforced by | Test status |
|---------------------------|-------------|-------------|
| `unitPrice` with 3 or more decimal places | `@Digits(integer = 10, fraction = 2)` | No test |
| `quantity` above the upper bound | `@Max(1000)` | No test |
| Negative `quantity` | `@Min(1)` | No test (zero is covered) |
| Client supplies `status` / `totalAmount` / `cancelledAt` on create | Request DTO has no such fields to bind | No test — **manually verified** |

The last one was exercised by hand against a running app: a request sending `status: "DELIVERED"`, `totalAmount: 0.01`, `lineTotal: 0.01` and a fabricated `cancelledAt` returned `201` with `status: PENDING`, `totalAmount: 50.00`, `lineTotal: 50.00` and `cancelledAt: null`. Jackson does not reject the unknown fields, so the request succeeds and the injected values are simply discarded.

Closing these is roughly four short `@WebMvcTest` cases and is a reasonable first task if the suite is revisited.

### 17.6 Sprint 2 deviations

| # | Deviation | Reason |
|---|-----------|--------|
| 7 | **List parameters are explicit `@RequestParam`s**, not a Spring-resolved `Pageable` | §3.4 requires `400` for a negative `page`, but Spring's `PageableHandlerMethodArgumentResolver` silently clamps it to `0`. Parsing `page`, `size` and `sort` directly makes every documented status code reachable and unit-testable. |
| 8 | **`spring.data.web.pageable.*` properties from §12.1 not used** | Follows from deviation 7. The `100` maximum and `20` default now live in `OrderQueryParams` as constants, which the tests assert against directly. |
| 9 | **`controller.support` package added** | §6 listed no home for query-parameter parsing. It is web-layer concern, so it sits beside the controller rather than in the service. |
| 10 | **`@BatchSize(50)` chosen over a fetch join** | §4.4 anticipated this: a collection fetch join combined with a `Pageable` makes Hibernate paginate in memory. Measured at 3 queries batched versus 7 unbatched for 5 orders. |
| 11 | **Sort accepts a single property**, not a repeated `sort` parameter | Multi-property sorting was not required. Documented rather than silently ignoring extra values. |

The `PageImpl` warning in §3.3 was worth heeding: `PagedResponse` is returned instead, so the JSON contract does not depend on Spring's internal page serialization.

### 17.7 Sprint 3 deviations

The concurrency design in §5.4 was implemented as written; the differences are small and local.

| # | Deviation | Reason |
|---|-----------|--------|
| 12 | **`InvalidOrderStateException` has a private constructor and a `cannotCancel(status)` factory** | The message in §3.5 is cancel-specific. A factory keeps that wording in one place while leaving the type free to name other rejected transitions later. |
| 13 | **`CANCELLED` is a fully-qualified enum literal inside the JPQL**, not a bind parameter | The target status of a cancel is never in question, so making it a parameter would let a caller write any status through this method. `expectedStatus` stays a parameter because Sprint 4 may condition on a different one. |
| 14 | **Cancel costs one `UPDATE` plus one `SELECT` on success** | §5.4 requires a re-read after the bulk update, since `clearAutomatically` empties the persistence context. Mapping the pre-update entity instead would echo a stale `PENDING` to the client. |
| 15 | **`getOrderById` and cancel share a private `findOrThrow`** | Both need "load with items or `404`". Extracted rather than duplicated. |

Note that a cancel which loses the race is indistinguishable, from the caller's side, from cancelling an order that advanced minutes ago: both are a `409` naming the current status. That is intentional — the caller's next action is the same either way.

### 17.8 Sprint 4 deviations

| # | Deviation | Reason |
|---|-----------|--------|
| 16 | **The `test` profile disables the scheduler instead of speeding it up**, contradicting §10 and §13.5 | A fast global scheduler makes the suite racy rather than thorough. `@SpringBootTest` contexts are cached and outlive their class, so the scheduler keeps ticking underneath later tests and promotes the `PENDING` rows they assert on. One test re-enables it for itself and drops its context with `@DirtiesContext`. |
| 17 | **`orders.scheduler.enabled` added**, not in §12.1 | Needed for deviation 16, and useful in its own right: the smoke scripts assert on `PENDING` orders, so a reviewer can run them without racing a tick. Guarded with `@ConditionalOnProperty`, defaulting to on. |
| 18 | **The Gradle `test` task sets `spring.profiles.active=test`** | §10 assumed a `test` profile without saying who activates it. Doing it in the build means no test class has to remember `@ActiveProfiles`. |
| 19 | **No `initialDelay`** | With `fixedRate` alone the first run happens at startup, which promotes anything left `PENDING` by a previous process. That is the desired recovery behaviour described in §8. |

The scheduler's `WHERE status = 'PENDING'` is what makes §5.4 hold in both directions, and both orderings are now covered by tests at the repository and HTTP levels.

### 17.9 Sprint 5 deviations (Swagger UI)

§11 held up: springdoc **3.1.0** is the right line for Boot 4.1.1, and it resolved with swagger-ui 5.32.11 and swagger-core 2.2.52.

| # | Deviation | Reason |
|---|-----------|--------|
| 20 | **The controller now declares `produces`, and the create method `consumes`** | Not in §11. Without them springdoc documents every response as `*/*`, which understates the contract and makes generated clients guess at the media type. No test changed: they all already sent and expected JSON. |
| 21 | **`springdoc.api-docs.version=openapi_3_1` set explicitly** | §11 listed only the two `enabled` properties. Left at its default the document is emitted as OpenAPI 3.0, which cannot express a nullable type properly. On 3.1, `cancelledAt` is typed `["string","null"]` instead of carrying the 3.0-era `nullable: true` extension. |
| 22 | **The generated spec is asserted by tests** (`OpenApiDocumentationTest`, 7 tests) | §13 planned "UI renders, spec valid" as a manual check. The spec is built by runtime reflection, making it the one artefact that can silently degrade — a renamed DTO or an un-introspectable generic leaves the page loading and the contract wrong. The tests pin the operation set, the documented `409`/`404`, the resolved `PagedResponse<OrderResponse>` item ref, and the absence of server-owned fields in the request schema. |
| 23 | **Two `@Schema` details are cosmetic only** | Swagger UI renders the `unitPrice` example as `25` rather than `25.00`, because it parses the example as a number and drops trailing zeros. The payload stays valid and the API accepts it; forcing the display would mean typing the example as a string and lying about the schema. |

The smoke scripts grew from 29 to 33 checks, adding the spec version, the documented route set, the documented cancel `409`, and the UI redirect.
