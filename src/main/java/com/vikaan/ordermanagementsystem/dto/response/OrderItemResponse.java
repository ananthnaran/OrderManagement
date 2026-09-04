package com.vikaan.ordermanagementsystem.dto.response;

import java.math.BigDecimal;

public record OrderItemResponse(
        String productId,
        String productName,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal
) {
}
