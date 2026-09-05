#!/usr/bin/env bash
# Smoke test for the Ecommerce Order Processing System (Sprints 1-3).
# Usage: ./docs/smoke-test.sh [base-url]      (default http://localhost:8080)
set -uo pipefail

BASE="${1:-http://localhost:8080}"
ORDERS="$BASE/api/v1/orders"
pass=0
fail=0

check() {
  local label="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    printf '  PASS  %-46s %s\n' "$label" "$actual"
    pass=$((pass + 1))
  else
    printf '  FAIL  %-46s expected %s, got %s\n' "$label" "$expected" "$actual"
    fail=$((fail + 1))
  fi
}

status_of() {
  curl -s -o /dev/null -w '%{http_code}' "$@"
}

echo "Order API smoke test against $BASE"
echo

# 1. Create an order with multiple items -> 201
created=$(curl -s -X POST "$ORDERS" \
  -H 'Content-Type: application/json' \
  -d '{
        "customerId": "CUST-1001",
        "items": [
          {"productId":"SKU-MOUSE","productName":"Wireless Mouse","quantity":2,"unitPrice":25.00},
          {"productId":"SKU-PAD","productName":"Mouse Pad","quantity":1,"unitPrice":10.00}
        ]
      }')

order_id=$(printf '%s' "$created" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
total=$(printf '%s' "$created" | sed -n 's/.*"totalAmount":\([0-9.]*\).*/\1/p')

if [ -n "$order_id" ]; then
  printf '  PASS  %-46s %s\n' "create order" "$order_id"
  pass=$((pass + 1))
else
  printf '  FAIL  %-46s %s\n' "create order" "$created"
  fail=$((fail + 1))
fi
check "create computes totalAmount" "60.00" "$total"

# 2. Retrieve it -> 200
check "get order by id" "200" "$(status_of "$ORDERS/$order_id")"

# 3. Unknown id -> 404
check "get unknown id" "404" \
  "$(status_of "$ORDERS/11111111-2222-3333-4444-555555555555")"

# 4. Malformed uuid -> 400
check "get malformed uuid" "400" "$(status_of "$ORDERS/not-a-uuid")"

# 5. Empty items -> 400
check "create with empty items" "400" \
  "$(status_of -X POST "$ORDERS" -H 'Content-Type: application/json' \
     -d '{"customerId":"CUST-1001","items":[]}')"

# 6. Blank customerId -> 400
check "create with blank customerId" "400" \
  "$(status_of -X POST "$ORDERS" -H 'Content-Type: application/json' \
     -d '{"customerId":"  ","items":[{"productId":"SKU-A","productName":"A","quantity":1,"unitPrice":10.00}]}')"

# 7. Zero quantity -> 400
check "create with quantity 0" "400" \
  "$(status_of -X POST "$ORDERS" -H 'Content-Type: application/json' \
     -d '{"customerId":"CUST-1","items":[{"productId":"SKU-A","productName":"A","quantity":0,"unitPrice":10.00}]}')"

# 8. Negative unit price -> 400
check "create with negative unitPrice" "400" \
  "$(status_of -X POST "$ORDERS" -H 'Content-Type: application/json' \
     -d '{"customerId":"CUST-1","items":[{"productId":"SKU-A","productName":"A","quantity":1,"unitPrice":-1.00}]}')"

# 9. Duplicate productId -> 400
check "create with duplicate productId" "400" \
  "$(status_of -X POST "$ORDERS" -H 'Content-Type: application/json' \
     -d '{"customerId":"CUST-1","items":[{"productId":"SKU-D","productName":"A","quantity":1,"unitPrice":5.00},{"productId":"SKU-D","productName":"B","quantity":2,"unitPrice":5.00}]}')"

# 10. Malformed JSON -> 400
check "create with malformed JSON" "400" \
  "$(status_of -X POST "$ORDERS" -H 'Content-Type: application/json' -d '{"customerId": ')"

# --- list, filter, paginate (Sprint 2) ---

# 11. List -> 200
check "list orders" "200" "$(status_of "$ORDERS")"

# 12. Default sort is newest first
listed=$(curl -s "$ORDERS")
sort_field=$(printf '%s' "$listed" | sed -n 's/.*"sort":"\([^"]*\)".*/\1/p')
check "list defaults to createdAt DESC" "createdAt: DESC" "$sort_field"

# 13. Filter by status -> 200
check "list filtered by status" "200" "$(status_of "$ORDERS?status=PENDING")"

# 14. Filter with no matches -> 200 and an empty page
empty=$(curl -s "$ORDERS?status=SHIPPED")
empty_total=$(printf '%s' "$empty" | sed -n 's/.*"totalElements":\([0-9]*\).*/\1/p')
check "list with no matches is empty not 404" "0" "$empty_total"

# 15. Oversized page size is clamped to 100
clamped=$(curl -s "$ORDERS?size=5000")
clamped_size=$(printf '%s' "$clamped" | sed -n 's/.*"size":\([0-9]*\).*/\1/p')
check "oversized page size is clamped" "100" "$clamped_size"

# 16. Wrongly cased status -> 400
check "list with lowercase status" "400" "$(status_of "$ORDERS?status=pending")"

# 17. Unknown status -> 400
check "list with unknown status" "400" "$(status_of "$ORDERS?status=FOO")"

# 18. Non-whitelisted sort property -> 400 (not 500)
check "list with unsortable property" "400" "$(status_of "$ORDERS?sort=dropTable,asc")"

# 19. Negative page -> 400
check "list with negative page" "400" "$(status_of "$ORDERS?page=-1")"

# 20. Zero page size -> 400
check "list with zero size" "400" "$(status_of "$ORDERS?size=0")"

# --- cancel (Sprint 3) ---

# 21. Cancel a PENDING order -> CANCELLED
cancel_created=$(curl -s -X POST "$ORDERS" -H 'Content-Type: application/json' \
  -d '{"customerId":"CUST-CANCEL","items":[
        {"productId":"SKU-MOUSE","productName":"Wireless Mouse","quantity":1,"unitPrice":25.00}]}')
cancel_id=$(printf '%s' "$cancel_created" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')

cancelled=$(curl -s -X POST "$ORDERS/$cancel_id/cancel")
cancelled_status=$(printf '%s' "$cancelled" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p')
check "cancel a pending order" "CANCELLED" "$cancelled_status"

# 22. cancelledAt is populated on the way out
cancelled_at=$(printf '%s' "$cancelled" | sed -n 's/.*"cancelledAt":"\([^"]*\)".*/\1/p')
if [ -n "$cancelled_at" ]; then
  printf '  PASS  %-46s %s\n' "cancel sets cancelledAt" "$cancelled_at"
  pass=$((pass + 1))
else
  printf '  FAIL  %-46s %s\n' "cancel sets cancelledAt" "null"
  fail=$((fail + 1))
fi

# 23. Cancelling twice -> 409, not a silent success
check "cancel an already cancelled order" "409" \
  "$(status_of -X POST "$ORDERS/$cancel_id/cancel")"

# 24. The conflict names the status the order is actually in
conflict=$(curl -s -X POST "$ORDERS/$cancel_id/cancel")
conflict_msg=$(printf '%s' "$conflict" | sed -n 's/.*"message":"\([^"]*\)".*/\1/p')
check "conflict names the current status" \
  "Order can be cancelled only while PENDING. Current status: CANCELLED" "$conflict_msg"

# 25. The cancelled order is reachable through the status filter
if curl -s "$ORDERS?status=CANCELLED" | grep -q "$cancel_id"; then
  printf '  PASS  %-46s %s\n' "cancelled order appears in the filter" "$cancel_id"
  pass=$((pass + 1))
else
  printf '  FAIL  %-46s %s\n' "cancelled order appears in the filter" "missing"
  fail=$((fail + 1))
fi

# 26. Cancel an unknown id -> 404, never a conflict
check "cancel unknown id" "404" \
  "$(status_of -X POST "$ORDERS/11111111-2222-3333-4444-555555555555/cancel")"

# 27. Cancel a malformed uuid -> 400
check "cancel malformed uuid" "400" "$(status_of -X POST "$ORDERS/not-a-uuid/cancel")"

echo
echo "passed=$pass failed=$fail"
[ "$fail" -eq 0 ] || exit 1
