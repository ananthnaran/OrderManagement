package com.vikaan.ordermanagementsystem.dto.response;

import com.vikaan.ordermanagementsystem.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String customerId,
        OrderStatus status,
        BigDecimal totalAmount,
        List<OrderItemResponse> items,
        Instant createdAt,
        Instant updatedAt,
        Instant cancelledAt
) {
}
