package com.vikaan.ordermanagementsystem.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "One line of an order, with the price snapshot taken at purchase time")
public record OrderItemResponse(

        @Schema(description = "Product identifier", example = "SKU-MOUSE")
        String productId,

        @Schema(description = "Product name as it was at purchase time", example = "Wireless Mouse")
        String productName,

        @Schema(description = "How many units", example = "2")
        Integer quantity,

        @Schema(description = "Price per unit at purchase time", example = "25.00")
        BigDecimal unitPrice,

        @Schema(description = "`quantity` times `unitPrice`, computed by the server", example = "50.00")
        BigDecimal lineTotal
) {
}
