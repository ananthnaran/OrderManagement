package com.vikaan.ordermanagementsystem.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateOrderRequest(

        @NotBlank(message = "customerId is required")
        @Size(max = 64, message = "customerId must be at most 64 characters")
        String customerId,

        @NotEmpty(message = "an order must contain at least one item")
        @Valid
        List<OrderItemRequest> items
) {
}
