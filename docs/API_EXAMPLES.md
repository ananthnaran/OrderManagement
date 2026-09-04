# API Examples — curl Collection

Runnable `curl` calls for the Ecommerce Order Processing System. Every command in the "Available now" sections was executed against the running application and produced the response shown.

- Base URL: `http://localhost:8080`
- Start the app: `./gradlew bootRun`
- Design reference: [`DESIGN.md`](./DESIGN.md) · Delivery plan: [`SPRINT_PLAN.md`](./SPRINT_PLAN.md)

## Endpoint status

| Method | Path | Purpose | Status |
|--------|------|---------|--------|
| `POST` | `/api/v1/orders` | Create an order with multiple items | **Available (Sprint 1)** |
| `GET` | `/api/v1/orders/{orderId}` | Retrieve an order by ID | **Available (Sprint 1)** |
| `GET` | `/api/v1/orders` | List orders, filter by status, paginate | **Available (Sprint 2)** |
| `POST` | `/api/v1/orders/{orderId}/cancel` | Cancel a `PENDING` order | Planned — Sprint 3 |

The planned routes are not implemented yet. Calling them today returns `404` (no handler), not a business error.

---

## Windows quoting warning

On **PowerShell**, JSON passed inline to `curl.exe` loses its quotes before curl receives it, and the API correctly answers `400 Malformed JSON request body`. Both of these **fail** on PowerShell 5.1:

```powershell
# BROKEN on PowerShell - quotes are stripped
curl.exe -X POST http://localhost:8080/api/v1/orders -H "Content-Type: application/json" -d '{"customerId":"CUST-1001"}'

# ALSO BROKEN on PowerShell - backslash escapes are mangled
curl.exe -X POST http://localhost:8080/api/v1/orders -H "Content-Type: application/json" -d "{\"customerId\":\"CUST-1001\"}"
```

Use **stdin** (`-d "@-"`) or a **file** (`-d "@order.json"`) instead. Both are shown below. The single-quoted form works normally in bash, zsh, and Git Bash.

---

## 1. Create an order with multiple items

### bash / zsh / Git Bash

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

### PowerShell

```powershell
$json = @'
{
  "customerId": "CUST-1001",
  "items": [
    {"productId":"SKU-MOUSE","productName":"Wireless Mouse","quantity":2,"unitPrice":25.00},
    {"productId":"SKU-PAD","productName":"Mouse Pad","quantity":1,"unitPrice":10.00}
  ]
}
'@
$json | curl.exe -i -X POST http://localhost:8080/api/v1/orders `
  -H "Content-Type: application/json" -d "@-"
```

**`201 Created`**, with `Location: /api/v1/orders/8eaba49c-0f18-4768-9a0e-170cce9bb7fa`:

```json
{
  "id": "8eaba49c-0f18-4768-9a0e-170cce9bb7fa",
  "customerId": "CUST-1001",
  "status": "PENDING",
  "totalAmount": 60.00,
  "items": [
    {"productId":"SKU-MOUSE","productName":"Wireless Mouse","quantity":2,"unitPrice":25.00,"lineTotal":50.00},
    {"productId":"SKU-PAD","productName":"Mouse Pad","quantity":1,"unitPrice":10.00,"lineTotal":10.00}
  ],
  "createdAt": "2026-09-03T17:29:48.915710Z",
  "updatedAt": "2026-09-03T17:29:48.915710Z",
  "cancelledAt": null
}
```

`status`, `totalAmount` and `lineTotal` are all computed server-side. Sending them in the request has no effect — the request DTO has no such fields to bind.

---

## 2. Retrieve an order by ID

```bash
curl -s http://localhost:8080/api/v1/orders/8eaba49c-0f18-4768-9a0e-170cce9bb7fa
```

```powershell
curl.exe -s http://localhost:8080/api/v1/orders/8eaba49c-0f18-4768-9a0e-170cce9bb7fa
```

**`200 OK`** returning the same body as above.

### Create and read back in one step

```bash
ORDER_ID=$(curl -s -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"CUST-1001","items":[{"productId":"SKU-A","productName":"Item A","quantity":3,"unitPrice":19.99}]}' \
  | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')

curl -s "http://localhost:8080/api/v1/orders/$ORDER_ID"
```

```powershell
$json = '{"customerId":"CUST-1001","items":[{"productId":"SKU-A","productName":"Item A","quantity":3,"unitPrice":19.99}]}'
$order = ($json | curl.exe -s -X POST http://localhost:8080/api/v1/orders `
  -H "Content-Type: application/json" -d "@-") | ConvertFrom-Json

curl.exe -s "http://localhost:8080/api/v1/orders/$($order.id)"
```

That order totals `59.97` (3 × 19.99), confirming `BigDecimal` arithmetic rather than floating point.

---

## 3. Error cases

Every failure returns the same shape (`timestamp`, `status`, `error`, `message`, `path`, `details`). Add `-w '\n%{http_code}\n'` to print the status code.

### Unknown order ID — `404`

```bash
curl -s http://localhost:8080/api/v1/orders/11111111-2222-3333-4444-555555555555
```

```json
{
  "timestamp": "2026-09-03T15:37:27.924838300Z",
  "status": 404,
  "error": "Not Found",
  "message": "Order not found: 11111111-2222-3333-4444-555555555555",
  "path": "/api/v1/orders/11111111-2222-3333-4444-555555555555",
  "details": []
}
```

### Malformed UUID — `400`

```bash
curl -s http://localhost:8080/api/v1/orders/not-a-uuid
```

```json
{
  "timestamp": "2026-09-03T15:37:28.067404400Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid value for 'orderId': not-a-uuid",
  "path": "/api/v1/orders/not-a-uuid",
  "details": []
}
```

A well-formed but unknown ID gives `404`; an unparseable ID gives `400`.

### No items and blank customer — `400` with field details

```bash
curl -s -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"","items":[]}'
```

```json
{
  "timestamp": "2026-09-03T15:37:28.009560600Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Request validation failed",
  "path": "/api/v1/orders",
  "details": [
    "customerId: customerId is required",
    "items: an order must contain at least one item"
  ]
}
```

### Zero quantity — `400` from the nested item constraint

```bash
curl -s -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"CUST-1","items":[{"productId":"SKU-A","productName":"A","quantity":0,"unitPrice":5.00}]}'
```

```json
{
  "status": 400,
  "message": "Request validation failed",
  "details": ["items[0].quantity: quantity must be at least 1"]
}
```

The `details` entry is indexed, so the offending line item is identifiable.

### Negative unit price — `400`

```bash
curl -s -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"CUST-1","items":[{"productId":"SKU-A","productName":"A","quantity":1,"unitPrice":-1.00}]}'
```

### Duplicate productId — `400`

```bash
curl -s -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"CUST-1","items":[
        {"productId":"SKU-D","productName":"A","quantity":1,"unitPrice":5.00},
        {"productId":"SKU-D","productName":"B","quantity":2,"unitPrice":5.00}]}'
```

```json
{
  "status": 400,
  "message": "Duplicate productId in request: SKU-D",
  "details": []
}
```

The same SKU twice in one order is rejected rather than merged, so a probable client bug is not silently absorbed.

### Malformed JSON — `400`, not `500`

```bash
curl -s -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId": '
```

```json
{
  "status": 400,
  "message": "Malformed JSON request body",
  "details": []
}
```

---

## 4. List orders (paginated)

Same syntax in both shells — no request body, so no quoting trap.

```bash
curl -s 'http://localhost:8080/api/v1/orders'
```

**`200 OK`** with the pagination envelope:

```json
{
  "content": [ /* orders, newest first */ ],
  "page": 0,
  "size": 20,
  "totalElements": 3,
  "totalPages": 1,
  "first": true,
  "last": true,
  "sort": "createdAt: DESC"
}
```

An empty result is `200` with `"content": []` and `"totalElements": 0` — never a `404`.

### Query parameters

| Param | Default | Rules |
|-------|---------|-------|
| `status` | none (all) | A valid `OrderStatus` name, uppercase. Invalid → `400`. |
| `page` | `0` | Must be `>= 0`. Negative → `400`. |
| `size` | `20` | Must be `>= 1`. Values above `100` are clamped to `100`. |
| `sort` | `createdAt,desc` | Property must be `createdAt`, `updatedAt`, `totalAmount` or `status`. Anything else → `400`. Direction defaults to `asc` when omitted. |

```bash
# Filter by status
curl -s 'http://localhost:8080/api/v1/orders?status=PENDING'

# Second page of two
curl -s 'http://localhost:8080/api/v1/orders?page=1&size=2'

# Cheapest orders first
curl -s 'http://localhost:8080/api/v1/orders?sort=totalAmount,asc'

# Oldest first
curl -s 'http://localhost:8080/api/v1/orders?sort=createdAt,asc'
```

In PowerShell, wrap the URL in quotes so `&` is not treated as a command separator:

```powershell
curl.exe -s "http://localhost:8080/api/v1/orders?page=1&size=2"
```

### List error cases

Invalid status — `400`, and the response lists what is accepted:

```bash
curl -s 'http://localhost:8080/api/v1/orders?status=pending'
```

```json
{
  "status": 400,
  "message": "Invalid status 'pending'. Accepted values: PENDING, PROCESSING, SHIPPED, DELIVERED, CANCELLED",
  "path": "/api/v1/orders",
  "details": []
}
```

Status values are case-sensitive, so `pending` is rejected rather than guessed at.

Non-sortable property — `400` rather than the `500` an unvalidated property would cause inside Hibernate:

```bash
curl -s 'http://localhost:8080/api/v1/orders?sort=dropTable,asc'
```

```json
{
  "status": 400,
  "message": "Cannot sort by 'dropTable'. Sortable properties: createdAt, status, totalAmount, updatedAt",
  "details": []
}
```

Out-of-range paging — both `400`:

```bash
curl -s 'http://localhost:8080/api/v1/orders?page=-1'     # page must be 0 or greater, was -1
curl -s 'http://localhost:8080/api/v1/orders?size=0'      # size must be at least 1, was 0
```

An oversized `size` is clamped instead of rejected, so this returns `200` with `"size": 100`:

```bash
curl -s 'http://localhost:8080/api/v1/orders?size=5000'
```

---

## 5. Run the whole collection at once

Both scripts exercise every call above and assert the status codes.

```bash
bash docs/smoke-test.sh                      # optional: pass a base URL
```

```powershell
.\docs\smoke-test.ps1                        # optional: -BaseUrl http://localhost:8080
```

Expected output:

```
Order API smoke test against http://localhost:8080

  PASS  create order                                   201
  PASS  create computes totalAmount                    60.00
  PASS  create starts as PENDING                       PENDING
  PASS  get order by id                                200
  PASS  get unknown id                                 404
  PASS  get malformed uuid                             400
  PASS  create with empty items                        400
  PASS  create with blank customerId                   400
  PASS  create with quantity 0                         400
  PASS  create with negative unitPrice                 400
  PASS  create with duplicate productId                400
  PASS  create with malformed JSON                     400
  PASS  list orders                                    200
  PASS  list defaults to createdAt DESC                createdAt: DESC
  PASS  list filtered by status                        200
  PASS  list with no matches is empty not 404          0
  PASS  oversized page size is clamped                 100
  PASS  list with lowercase status                     400
  PASS  list with unknown status                       400
  PASS  list with unsortable property                  400
  PASS  list with negative page                        400
  PASS  list with zero size                            400

passed=22 failed=0
```

Both scripts accept a base URL, so they can be pointed at another port: `bash docs/smoke-test.sh http://localhost:8081` or `.\docs\smoke-test.ps1 -BaseUrl http://localhost:8081`.

Each script exits non-zero if any check fails, so it can be dropped into CI as a post-deploy check.

---

## 6. Coming in later sprints

Shown for reference only — these do not work yet.

```bash
# Sprint 3 - cancel, allowed only while PENDING (409 otherwise)
curl -s -X POST "http://localhost:8080/api/v1/orders/$ORDER_ID/cancel"

# Sprint 5 - interactive docs
# http://localhost:8080/swagger-ui.html
```

Note that H2 runs in memory, so restarting the application clears every order created by these calls.
