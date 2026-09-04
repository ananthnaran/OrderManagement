package com.vikaan.ordermanagementsystem.mapper;

import com.vikaan.ordermanagementsystem.dto.response.OrderItemResponse;
import com.vikaan.ordermanagementsystem.dto.response.OrderResponse;
import com.vikaan.ordermanagementsystem.entity.Order;
import com.vikaan.ordermanagementsystem.entity.OrderItem;

import java.util.List;

public final class OrderMapper {

    private OrderMapper() {
    }

    public static OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(OrderMapper::toItemResponse)
                .toList();

        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getStatus(),
                order.getTotalAmount(),
                items,
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getCancelledAt()
        );
    }

    private static OrderItemResponse toItemResponse(OrderItem item) {
        return new OrderItemResponse(
                item.getProductId(),
                item.getProductName(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getLineTotal()
        );
    }
}
