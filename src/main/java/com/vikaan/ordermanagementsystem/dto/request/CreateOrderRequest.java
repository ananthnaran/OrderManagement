package com.vikaan.ordermanagementsystem.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = """
        A new order. There is deliberately no `status` or `totalAmount` field: both are derived \
        by the server, so a client cannot set them.""")
public record CreateOrderRequest(

        @Schema(description = "Identifier of the customer placing the order",
                example = "CUST-1001",
                maxLength = 64,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "customerId is required")
        @Size(max = 64, message = "customerId must be at most 64 characters")
        String customerId,

        @Schema(description = "At least one line item. Each `productId` may appear only once.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "an order must contain at least one item")
        @Valid
        List<OrderItemRequest> items
) {
}
