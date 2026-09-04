package com.vikaan.ordermanagementsystem.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record OrderItemRequest(

        @NotBlank(message = "productId is required")
        @Size(max = 64, message = "productId must be at most 64 characters")
        String productId,

        @NotBlank(message = "productName is required")
        @Size(max = 255, message = "productName must be at most 255 characters")
        String productName,

        @NotNull(message = "quantity is required")
        @Min(value = 1, message = "quantity must be at least 1")
        @Max(value = 1000, message = "quantity must not exceed 1000")
        Integer quantity,

        @NotNull(message = "unitPrice is required")
        @DecimalMin(value = "0.00", message = "unitPrice must not be negative")
        @Digits(integer = 10, fraction = 2, message = "unitPrice must have at most 2 decimal places")
        BigDecimal unitPrice
) {
}
