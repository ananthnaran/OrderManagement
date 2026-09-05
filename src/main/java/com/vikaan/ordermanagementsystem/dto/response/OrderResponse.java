package com.vikaan.ordermanagementsystem.dto.response;

import com.vikaan.ordermanagementsystem.entity.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "An order and its line items")
public record OrderResponse(

        @Schema(description = "Server-assigned order id",
                example = "3b84066a-93fd-4fe4-82f5-719e5f5202d6")
        UUID id,

        @Schema(description = "Identifier of the customer who placed the order", example = "CUST-1001")
        String customerId,

        @Schema(description = "Current status. A new order is always `PENDING`.", example = "PENDING")
        OrderStatus status,

        @Schema(description = "Sum of every `lineTotal`, computed by the server", example = "50.00")
        BigDecimal totalAmount,

        @Schema(description = "Line items, in the order they were submitted")
        List<OrderItemResponse> items,

        @Schema(description = "When the order was created, UTC", example = "2026-09-05T13:45:12.284611Z")
        Instant createdAt,

        @Schema(description = "When the order last changed, UTC. Equal to `createdAt` until "
                + "something changes it.",
                example = "2026-09-05T13:45:12.284611Z")
        Instant updatedAt,

        @Schema(description = "When the order was cancelled, UTC. `null` unless the status is "
                + "`CANCELLED`.",
                nullable = true,
                example = "null")
        Instant cancelledAt
) {
}
