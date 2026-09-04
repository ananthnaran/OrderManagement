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
}
