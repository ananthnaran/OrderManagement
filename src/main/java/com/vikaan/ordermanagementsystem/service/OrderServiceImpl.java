package com.vikaan.ordermanagementsystem.service;

import com.vikaan.ordermanagementsystem.dto.request.CreateOrderRequest;
import com.vikaan.ordermanagementsystem.dto.request.OrderItemRequest;
import com.vikaan.ordermanagementsystem.dto.response.OrderResponse;
import com.vikaan.ordermanagementsystem.dto.response.PagedResponse;
import com.vikaan.ordermanagementsystem.entity.Order;
import com.vikaan.ordermanagementsystem.entity.OrderItem;
import com.vikaan.ordermanagementsystem.entity.OrderStatus;
import com.vikaan.ordermanagementsystem.exception.InvalidRequestException;
import com.vikaan.ordermanagementsystem.exception.OrderNotFoundException;
import com.vikaan.ordermanagementsystem.mapper.OrderMapper;
import com.vikaan.ordermanagementsystem.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private static final int MONEY_SCALE = 2;

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private final OrderRepository orderRepository;
    private final Clock clock;

    @Override
    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        rejectDuplicateProducts(request.items());

        // TIMESTAMP(6) stores microseconds, so truncate to keep the response equal to the stored value
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Order order = Order.builder()
                .customerId(request.customerId())
                .status(OrderStatus.PENDING)
                .totalAmount(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .createdAt(now)
                .updatedAt(now)
                .build();

        BigDecimal total = BigDecimal.ZERO;
        for (OrderItemRequest item : request.items()) {
            BigDecimal unitPrice = item.unitPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal lineTotal = unitPrice
                    .multiply(BigDecimal.valueOf(item.quantity()))
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);

            order.addItem(OrderItem.builder()
                    .productId(item.productId())
                    .productName(item.productName())
                    .quantity(item.quantity())
                    .unitPrice(unitPrice)
                    .lineTotal(lineTotal)
                    .build());

            total = total.add(lineTotal);
        }
        order.setTotalAmount(total.setScale(MONEY_SCALE, RoundingMode.HALF_UP));

        Order saved = orderRepository.save(order);
        log.info("Created order {} for customer {} with {} item(s), total {}",
                saved.getId(), saved.getCustomerId(), saved.getItems().size(), saved.getTotalAmount());
        return OrderMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrderById(UUID orderId) {
        return orderRepository.findWithItemsById(orderId)
                .map(OrderMapper::toResponse)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<OrderResponse> listOrders(OrderStatus status, Pageable pageable) {
        Page<Order> orders = (status == null)
                ? orderRepository.findAll(pageable)
                : orderRepository.findByStatus(status, pageable);

        // Mapping happens inside the transaction so the batched `items` load can still run.
        return PagedResponse.from(orders.map(OrderMapper::toResponse));
    }

    private void rejectDuplicateProducts(List<OrderItemRequest> items) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (OrderItemRequest item : items) {
            if (!seen.add(item.productId())) {
                duplicates.add(item.productId());
            }
        }
        if (!duplicates.isEmpty()) {
            throw new InvalidRequestException(
                    "Duplicate productId in request: " + String.join(", ", duplicates));
        }
    }
}
