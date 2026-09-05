package com.vikaan.ordermanagementsystem.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "The single error shape returned by every failing request")
public record ErrorResponse(

        @Schema(description = "When the failure was handled, UTC", example = "2026-09-05T13:45:12.284611Z")
        Instant timestamp,

        @Schema(description = "HTTP status code", example = "400")
        int status,

        @Schema(description = "HTTP status reason phrase", example = "Bad Request")
        String error,

        @Schema(description = "What went wrong, safe to show a caller",
                example = "Validation failed for 2 field(s)")
        String message,

        @Schema(description = "The request path that failed", example = "/api/v1/orders")
        String path,

        @Schema(description = "Field-level messages. Empty when the failure is not field-specific.",
                example = "[\"items[0].quantity: quantity must be at least 1\"]")
        List<String> details
) {
}
