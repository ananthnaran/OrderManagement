package com.vikaan.ordermanagementsystem;

import com.vikaan.ordermanagementsystem.entity.Order;
import com.vikaan.ordermanagementsystem.entity.OrderItem;
import com.vikaan.ordermanagementsystem.entity.OrderStatus;
import com.vikaan.ordermanagementsystem.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
 * End-to-end coverage of cancellation over a real H2 database, including the two outcomes that
 * are easy to conflate: a missing order and one that has already left {@code PENDING}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderCancelIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-03T14:30:00Z");

    private static final String VALID_BODY = """
            {"customerId":"CUST-1001","items":[
              {"productId":"SKU-MOUSE","productName":"Wireless Mouse","quantity":2,"unitPrice":25.00}]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void resetDatabase() {
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("an order created over HTTP can be cancelled, and the cancellation is persisted")
    void createThenCancelThenGet() throws Exception {
        UUID orderId = createPendingOrder();

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());

        mockMvc.perform(get("/api/v1/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());

        Order stored = orderRepository.findWithItemsById(orderId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stored.getCancelledAt()).isNotNull();
        assertThat(stored.getUpdatedAt()).isEqualTo(stored.getCancelledAt());
    }

    @Test
    @DisplayName("cancelling leaves the order's items and total untouched")
    void cancelKeepsTheRestOfTheOrder() throws Exception {
        UUID orderId = createPendingOrder();

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("CUST-1001"))
                .andExpect(jsonPath("$.totalAmount").value(50.00))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value("SKU-MOUSE"));
    }

    @Test
    @DisplayName("cancelling twice returns 409 the second time rather than succeeding silently")
    void secondCancelIsRejected() throws Exception {
        UUID orderId = createPendingOrder();

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(containsString("Current status: CANCELLED")));

        Order stored = orderRepository.findWithItemsById(orderId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PROCESSING", "SHIPPED", "DELIVERED"})
    @DisplayName("cancelling an order that has already advanced returns 409 and does not change it")
    void cancelIsRejectedOnceTheOrderHasAdvanced(OrderStatus status) throws Exception {
        Order order = persistOrder(status);

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", order.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("Current status: " + status)));

        Order stored = orderRepository.findWithItemsById(order.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(status);
        assertThat(stored.getCancelledAt()).isNull();
    }

    @Test
    @DisplayName("a cancelled order moves out of the PENDING filter and into the CANCELLED one")
    void cancelledOrderMovesBetweenStatusFilters() throws Exception {
        UUID orderId = createPendingOrder();

        mockMvc.perform(get("/api/v1/orders").param("status", "PENDING"))
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/orders").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/orders").param("status", "CANCELLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(orderId.toString()));
    }

    @Test
    @DisplayName("cancelling an unknown id returns 404, never a conflict")
    void cancelUnknownOrderReturnsNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/orders/{id}/cancel", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("cancelling a malformed id returns 400")
    void cancelMalformedIdReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/orders/not-a-uuid/cancel"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
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

    private Order persistOrder(OrderStatus status) {
        Order order = Order.builder()
                .customerId("CUST-1001")
                .status(status)
                .totalAmount(new BigDecimal("50.00"))
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
        order.addItem(OrderItem.builder()
                .productId("SKU-MOUSE")
                .productName("Wireless Mouse")
                .quantity(2)
                .unitPrice(new BigDecimal("25.00"))
                .lineTotal(new BigDecimal("50.00"))
                .build());
        return orderRepository.saveAndFlush(order);
    }
}
