package com.vikaan.ordermanagementsystem;

import com.vikaan.ordermanagementsystem.entity.Order;
import com.vikaan.ordermanagementsystem.entity.OrderItem;
import com.vikaan.ordermanagementsystem.entity.OrderStatus;
import com.vikaan.ordermanagementsystem.repository.OrderRepository;
import com.vikaan.ordermanagementsystem.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Promotion over a real database, including the two orderings of cancel and promote that the
 * whole conditional-update design exists to survive. The service method is called directly, so
 * no test waits out a scheduler interval.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderPromotionIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-03T14:30:00Z");

    private static final String VALID_BODY = """
            {"customerId":"CUST-1001","items":[
              {"productId":"SKU-MOUSE","productName":"Wireless Mouse","quantity":1,"unitPrice":25.00}]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void resetDatabase() {
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("a newly created order is promoted to PROCESSING on the next run")
    void createdOrderIsPromoted() throws Exception {
        UUID orderId = createPendingOrder();

        assertThat(orderService.promotePendingOrders()).isEqualTo(1);

        mockMvc.perform(get("/api/v1/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.cancelledAt").doesNotExist());

        mockMvc.perform(get("/api/v1/orders").param("status", "PROCESSING"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/v1/orders").param("status", "PENDING"))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("a run promotes every pending order at once and leaves the others alone")
    void promotionMovesTheWholePendingBatch() {
        persistOrder(OrderStatus.PENDING);
        persistOrder(OrderStatus.PENDING);
        persistOrder(OrderStatus.PENDING);
        persistOrder(OrderStatus.SHIPPED);
        persistOrder(OrderStatus.DELIVERED);

        assertThat(orderService.promotePendingOrders()).isEqualTo(3);
        assertThat(orderService.promotePendingOrders()).isZero();
    }

    @Test
    @DisplayName("a run with nothing pending is a silent no-op")
    void promotionWithNothingPendingIsSilent() {
        assertThat(orderService.promotePendingOrders()).isZero();
    }

    /**
     * The regression this sprint exists for. A read-then-write scheduler would reload the order,
     * still see PENDING in memory, and write PROCESSING over the cancellation.
     */
    @Test
    @DisplayName("an order cancelled before a run is never resurrected as PROCESSING")
    void cancelledOrderIsNeverResurrected() throws Exception {
        UUID orderId = createPendingOrder();

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(orderService.promotePendingOrders()).isZero();

        mockMvc.perform(get("/api/v1/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());
    }

    @Test
    @DisplayName("an order promoted before a cancel attempt yields 409 and stays PROCESSING")
    void promotedOrderCanNoLongerBeCancelled() throws Exception {
        UUID orderId = createPendingOrder();

        assertThat(orderService.promotePendingOrders()).isEqualTo(1);

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("Current status: PROCESSING")));

        Order stored = orderRepository.findWithItemsById(orderId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.PROCESSING);
        assertThat(stored.getCancelledAt()).isNull();
    }

    @Test
    @DisplayName("promotion leaves the order's customer, items and total untouched")
    void promotionChangesNothingButStatus() throws Exception {
        UUID orderId = createPendingOrder();

        orderService.promotePendingOrders();

        mockMvc.perform(get("/api/v1/orders/{id}", orderId))
                .andExpect(jsonPath("$.customerId").value("CUST-1001"))
                .andExpect(jsonPath("$.totalAmount").value(25.00))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].lineTotal").value(25.00));
    }

    private UUID createPendingOrder() throws Exception {
        String location = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");

        assertThat(location).isNotNull();
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private void persistOrder(OrderStatus status) {
        Order order = Order.builder()
                .customerId("CUST-1001")
                .status(status)
                .totalAmount(new BigDecimal("25.00"))
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
        order.addItem(OrderItem.builder()
                .productId("SKU-MOUSE")
                .productName("Wireless Mouse")
                .quantity(1)
                .unitPrice(new BigDecimal("25.00"))
                .lineTotal(new BigDecimal("25.00"))
                .build());
        orderRepository.saveAndFlush(order);
    }
}
