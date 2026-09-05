package com.vikaan.ordermanagementsystem.controller;

import com.vikaan.ordermanagementsystem.controller.support.OrderQueryParams;
import com.vikaan.ordermanagementsystem.dto.request.CreateOrderRequest;
import com.vikaan.ordermanagementsystem.dto.response.OrderResponse;
import com.vikaan.ordermanagementsystem.dto.response.PagedResponse;
import com.vikaan.ordermanagementsystem.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        OrderResponse created = orderService.createOrder(request);
        return ResponseEntity
                .created(URI.create("/api/v1/orders/" + created.id()))
                .body(created);
    }

    @GetMapping("/{orderId}")
    public OrderResponse getOrder(@PathVariable UUID orderId) {
        return orderService.getOrderById(orderId);
    }

    @GetMapping
    public PagedResponse<OrderResponse> listOrders(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
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
    public OrderResponse cancelOrder(@PathVariable UUID orderId) {
        return orderService.cancelOrder(orderId);
    }
}
