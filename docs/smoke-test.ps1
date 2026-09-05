# Smoke test for the Ecommerce Order Processing System (Sprints 1-3).
# Usage: .\docs\smoke-test.ps1 [-BaseUrl http://localhost:8080]
#
# JSON is piped to curl via stdin ("-d @-") on purpose. In PowerShell,
# inline JSON passed as -d '{"a":1}' or -d "{\"a\":1}" loses its quotes
# before curl.exe sees it and the API answers 400 Malformed JSON.

param([string]$BaseUrl = "http://localhost:8080")

$orders = "$BaseUrl/api/v1/orders"
$script:pass = 0
$script:fail = 0

function Check {
    param([string]$Label, [string]$Expected, [string]$Actual)
    if ($Expected -eq $Actual) {
        Write-Host ("  PASS  {0,-46} {1}" -f $Label, $Actual)
        $script:pass++
    } else {
        Write-Host ("  FAIL  {0,-46} expected {1}, got {2}" -f $Label, $Expected, $Actual)
        $script:fail++
    }
}

function PostJson {
    param([string]$Json)
    $body = New-TemporaryFile
    $code = $Json | curl.exe -s -o $body.FullName -w "%{http_code}" `
        -X POST $orders -H "Content-Type: application/json" -d "@-"
    $content = Get-Content $body.FullName -Raw
    Remove-Item $body.FullName -ErrorAction SilentlyContinue
    return [pscustomobject]@{ Code = $code; Body = $content }
}

function GetStatus {
    param([string]$Url)
    return (curl.exe -s -o NUL -w "%{http_code}" $Url)
}

function PostStatus {
    param([string]$Url)
    return (curl.exe -s -o NUL -w "%{http_code}" -X POST $Url)
}

Write-Host "Order API smoke test against $BaseUrl"
Write-Host ""

# 1. Create an order with multiple items -> 201
$createJson = @'
{
  "customerId": "CUST-1001",
  "items": [
    {"productId":"SKU-MOUSE","productName":"Wireless Mouse","quantity":2,"unitPrice":25.00},
    {"productId":"SKU-PAD","productName":"Mouse Pad","quantity":1,"unitPrice":10.00}
  ]
}
'@
$created = PostJson $createJson
Check "create order" "201" $created.Code

$order = $created.Body | ConvertFrom-Json
$orderId = $order.id
Check "create computes totalAmount" "60.00" ([string]$order.totalAmount)
Check "create starts as PENDING" "PENDING" $order.status

# 2. Retrieve it -> 200
Check "get order by id" "200" (GetStatus "$orders/$orderId")

# 3. Unknown id -> 404
Check "get unknown id" "404" (GetStatus "$orders/11111111-2222-3333-4444-555555555555")

# 4. Malformed uuid -> 400
Check "get malformed uuid" "400" (GetStatus "$orders/not-a-uuid")

# 5. Empty items -> 400
Check "create with empty items" "400" (PostJson '{"customerId":"CUST-1001","items":[]}').Code

# 6. Blank customerId -> 400
Check "create with blank customerId" "400" (PostJson '{"customerId":"  ","items":[{"productId":"SKU-A","productName":"A","quantity":1,"unitPrice":10.00}]}').Code

# 7. Zero quantity -> 400
Check "create with quantity 0" "400" (PostJson '{"customerId":"CUST-1","items":[{"productId":"SKU-A","productName":"A","quantity":0,"unitPrice":10.00}]}').Code

# 8. Negative unit price -> 400
Check "create with negative unitPrice" "400" (PostJson '{"customerId":"CUST-1","items":[{"productId":"SKU-A","productName":"A","quantity":1,"unitPrice":-1.00}]}').Code

# 9. Duplicate productId -> 400
Check "create with duplicate productId" "400" (PostJson '{"customerId":"CUST-1","items":[{"productId":"SKU-D","productName":"A","quantity":1,"unitPrice":5.00},{"productId":"SKU-D","productName":"B","quantity":2,"unitPrice":5.00}]}').Code

# 10. Malformed JSON -> 400
Check "create with malformed JSON" "400" (PostJson '{"customerId": ').Code

# --- list, filter, paginate (Sprint 2) ---

# 11. List -> 200
Check "list orders" "200" (GetStatus $orders)

# 12. Default sort is newest first
$listed = (curl.exe -s $orders) | ConvertFrom-Json
Check "list defaults to createdAt DESC" "createdAt: DESC" $listed.sort

# 13. Filter by status -> 200
Check "list filtered by status" "200" (GetStatus "$orders`?status=PENDING")

# 14. Filter with no matches -> 200 and an empty page
$empty = (curl.exe -s "$orders`?status=SHIPPED") | ConvertFrom-Json
Check "list with no matches is empty not 404" "0" ([string]$empty.totalElements)

# 15. Oversized page size is clamped to 100
$clamped = (curl.exe -s "$orders`?size=5000") | ConvertFrom-Json
Check "oversized page size is clamped" "100" ([string]$clamped.size)

# 16. Wrongly cased status -> 400
Check "list with lowercase status" "400" (GetStatus "$orders`?status=pending")

# 17. Unknown status -> 400
Check "list with unknown status" "400" (GetStatus "$orders`?status=FOO")

# 18. Non-whitelisted sort property -> 400 (not 500)
Check "list with unsortable property" "400" (GetStatus "$orders`?sort=dropTable,asc")

# 19. Negative page -> 400
Check "list with negative page" "400" (GetStatus "$orders`?page=-1")

# 20. Zero page size -> 400
Check "list with zero size" "400" (GetStatus "$orders`?size=0")

# --- cancel (Sprint 3) ---

# 21. Cancel a PENDING order -> CANCELLED
$cancelJson = @'
{"customerId":"CUST-CANCEL","items":[
  {"productId":"SKU-MOUSE","productName":"Wireless Mouse","quantity":1,"unitPrice":25.00}]}
'@
$cancelId = ((PostJson $cancelJson).Body | ConvertFrom-Json).id
$cancelled = (curl.exe -s -X POST "$orders/$cancelId/cancel") | ConvertFrom-Json
Check "cancel a pending order" "CANCELLED" $cancelled.status

# 22. cancelledAt is populated on the way out
Check "cancel sets cancelledAt" "True" ([string]($null -ne $cancelled.cancelledAt))

# 23. Cancelling twice -> 409, not a silent success
Check "cancel an already cancelled order" "409" (PostStatus "$orders/$cancelId/cancel")

# 24. The conflict names the status the order is actually in
$conflict = (curl.exe -s -X POST "$orders/$cancelId/cancel") | ConvertFrom-Json
Check "conflict names the current status" `
    "Order can be cancelled only while PENDING. Current status: CANCELLED" $conflict.message

# 25. The cancelled order is reachable through the status filter
$cancelledList = (curl.exe -s "$orders`?status=CANCELLED") | ConvertFrom-Json
Check "cancelled order appears in the filter" "True" ([string]($cancelledList.content.id -contains $cancelId))

# 26. Cancel an unknown id -> 404, never a conflict
Check "cancel unknown id" "404" (PostStatus "$orders/11111111-2222-3333-4444-555555555555/cancel")

# 27. Cancel a malformed uuid -> 400
Check "cancel malformed uuid" "400" (PostStatus "$orders/not-a-uuid/cancel")

Write-Host ""
Write-Host "passed=$script:pass failed=$script:fail"
if ($script:fail -ne 0) { exit 1 }
