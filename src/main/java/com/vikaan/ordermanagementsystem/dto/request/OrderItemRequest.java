package com.vikaan.ordermanagementsystem.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(description = """
        One line of an order. `productName` and `unitPrice` are snapshotted at purchase time, so \
        renaming or repricing a product later does not rewrite historical orders.""")
public record OrderItemRequest(

        @Schema(description = "Product identifier, unique within the order",
                example = "SKU-MOUSE",
                maxLength = 64,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "productId is required")
        @Size(max = 64, message = "productId must be at most 64 characters")
        String productId,

        @Schema(description = "Product name as it was at purchase time",
                example = "Wireless Mouse",
                maxLength = 255,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "productName is required")
        @Size(max = 255, message = "productName must be at most 255 characters")
        String productName,

        @Schema(description = "How many units, between 1 and 1000",
                example = "2",
                minimum = "1",
                maximum = "1000",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "quantity is required")
        @Min(value = 1, message = "quantity must be at least 1")
        @Max(value = 1000, message = "quantity must not exceed 1000")
        Integer quantity,

        @Schema(description = "Price per unit, at most two decimal places",
                example = "25.00",
                minimum = "0.00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "unitPrice is required")
        @DecimalMin(value = "0.00", message = "unitPrice must not be negative")
        @Digits(integer = 10, fraction = 2, message = "unitPrice must have at most 2 decimal places")
        BigDecimal unitPrice
) {
}
