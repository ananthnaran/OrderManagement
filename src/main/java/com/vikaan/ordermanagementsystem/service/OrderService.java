package com.vikaan.ordermanagementsystem.service;

import com.vikaan.ordermanagementsystem.dto.request.CreateOrderRequest;
import com.vikaan.ordermanagementsystem.dto.response.OrderResponse;
import com.vikaan.ordermanagementsystem.dto.response.PagedResponse;
import com.vikaan.ordermanagementsystem.entity.OrderStatus;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface OrderService {

    OrderResponse createOrder(CreateOrderRequest request);

    OrderResponse getOrderById(UUID orderId);

    /**
     * @param status optional filter; {@code null} lists every order
     */
    PagedResponse<OrderResponse> listOrders(OrderStatus status, Pageable pageable);

    /**
     * Cancels an order that is still {@code PENDING}.
     *
     * @throws com.vikaan.ordermanagementsystem.exception.OrderNotFoundException     no such order
     * @throws com.vikaan.ordermanagementsystem.exception.InvalidOrderStateException the order has
     *                                                                              already left
     *                                                                              {@code PENDING}
     */
    OrderResponse cancelOrder(UUID orderId);

    /**
     * Moves every {@code PENDING} order to {@code PROCESSING}. Called by the scheduler, and
     * directly by tests so no test has to wait out a real interval.
     *
     * @return how many orders were promoted; {@code 0} is a normal, silent outcome
     */
    int promotePendingOrders();
}
