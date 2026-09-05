package com.vikaan.ordermanagementsystem.controller;

import com.vikaan.ordermanagementsystem.controller.support.OrderQueryParams;
import com.vikaan.ordermanagementsystem.dto.request.CreateOrderRequest;
import com.vikaan.ordermanagementsystem.dto.response.ErrorResponse;
import com.vikaan.ordermanagementsystem.dto.response.OrderResponse;
import com.vikaan.ordermanagementsystem.dto.response.PagedResponse;
import com.vikaan.ordermanagementsystem.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
// The media types are declared rather than left open: without them the generated spec advertises
// every response as `*/*`, which is a worse contract than the API actually offers.
@RequestMapping(path = "/api/v1/orders", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Create, retrieve, list and cancel customer orders")
public class OrderController {

    private final OrderService orderService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Create an order",
            description = """
                    Creates an order with one or more line items. The server assigns the id, sets \
                    the status to `PENDING`, snapshots each product's name and price, and computes \
                    every `lineTotal` and the `totalAmount`. A `productId` may appear only once \
                    per order: a repeat is rejected rather than merged, because merging guesses at \
                    intent and makes the response disagree with the request.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description =
                    "Created. The `Location` header holds the URL of the new order."),
            @ApiResponse(responseCode = "400", description =
                    "Validation failure, a duplicate `productId`, or a malformed JSON body. "
                            + "Field-level messages are listed in `details`.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        OrderResponse created = orderService.createOrder(request);
        return ResponseEntity
                .created(URI.create("/api/v1/orders/" + created.id()))
                .body(created);
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Retrieve an order by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The order"),
            @ApiResponse(responseCode = "404", description = "No order with that id",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "400", description = "The id is not a valid UUID",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public OrderResponse getOrder(
            @Parameter(description = "Order id", example = "3b84066a-93fd-4fe4-82f5-719e5f5202d6")
            @PathVariable UUID orderId) {

        return orderService.getOrderById(orderId);
    }

    @GetMapping
    @Operation(
            summary = "List orders, optionally filtered by status",
            description = """
                    Returns a page of orders, newest first by default. An empty result is a `200` \
                    with `content: []`, never a `404`.

                    Sort properties are whitelisted. An unvalidated property would reach Hibernate \
                    and surface as a `500`, so anything outside the list is rejected with a `400` \
                    that names the properties which do work.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of orders"),
            @ApiResponse(responseCode = "400", description =
                    "Unknown or wrongly cased `status`, a non-sortable `sort` property, "
                            + "a negative `page`, or a `size` below 1.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public PagedResponse<OrderResponse> listOrders(
            @Parameter(description = "Filter by exact status. Case-sensitive; omit to list every order.",
                    schema = @Schema(allowableValues = {"PENDING", "PROCESSING", "SHIPPED", "DELIVERED", "CANCELLED"}))
            @RequestParam(required = false) String status,

            @Parameter(description = "Zero-based page index. Negative values are rejected.")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Page size. Must be at least 1; anything above 100 is clamped to 100.")
            @RequestParam(defaultValue = "20") int size,

            @Parameter(description = "`property,direction`. Direction defaults to `asc` when omitted.",
                    example = "createdAt,desc",
                    schema = @Schema(allowableValues = {
                            "createdAt,desc", "createdAt,asc",
                            "updatedAt,desc", "updatedAt,asc",
                            "totalAmount,desc", "totalAmount,asc",
                            "status,desc", "status,asc"}))
            @RequestParam(defaultValue = "createdAt,desc") String sort) {

        return orderService.listOrders(
                OrderQueryParams.parseStatus(status),
                OrderQueryParams.parsePageable(page, size, sort));
    }

    /**
     * A command rather than a {@code PATCH} on {@code status}: a general status patch would invite
     * arbitrary client-driven transitions, which requirement 6 forbids.
     */
    @PostMapping("/{orderId}/cancel")
    @Operation(
            summary = "Cancel an order, allowed only while it is PENDING",
            description = """
                    Modelled as a command rather than a `PATCH` on `status`, so a client cannot ask \
                    for an arbitrary transition.

                    The refusal is enforced by the SQL predicate \
                    `WHERE id = ? AND status = 'PENDING'`, and the affected row count decides the \
                    outcome. A promotion running at the same instant therefore cannot slip between \
                    a status check and the write: whichever statement commits first wins, and the \
                    other matches no rows.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description =
                    "Cancelled. The full order is returned with `cancelledAt` set."),
            @ApiResponse(responseCode = "409", description =
                    "The order has already left `PENDING`. The message names its current status, "
                            + "and the order is unchanged.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No order with that id",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "400", description = "The id is not a valid UUID",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public OrderResponse cancelOrder(
            @Parameter(description = "Order id", example = "3b84066a-93fd-4fe4-82f5-719e5f5202d6")
            @PathVariable UUID orderId) {

        return orderService.cancelOrder(orderId);
    }
}
