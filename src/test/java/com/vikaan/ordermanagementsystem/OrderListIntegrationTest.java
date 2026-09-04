package com.vikaan.ordermanagementsystem;

import com.vikaan.ordermanagementsystem.entity.Order;
import com.vikaan.ordermanagementsystem.entity.OrderItem;
import com.vikaan.ordermanagementsystem.entity.OrderStatus;
import com.vikaan.ordermanagementsystem.repository.OrderRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the list endpoint over a real H2 database: filtering, slicing and
 * ordering all the way from the HTTP query string to SQL and back.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderListIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-03T14:30:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void resetDatabase() {
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("an order created over HTTP is then visible in the list")
    void createdOrderAppearsInList() throws Exception {
        String body = """
                {"customerId":"CUST-1001","items":[
                  {"productId":"SKU-MOUSE","productName":"Wireless Mouse","quantity":2,"unitPrice":25.00}]}
                """;

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].customerId").value("CUST-1001"))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].totalAmount").value(50.00))
                .andExpect(jsonPath("$.content[0].items.length()").value(1));
    }

    @Test
    @DisplayName("the status filter narrows the list to matching orders only")
    void statusFilterNarrowsResults() throws Exception {
        persistOrder(OrderStatus.PENDING, NOW);
        persistOrder(OrderStatus.PENDING, NOW.plusSeconds(1));
        persistOrder(OrderStatus.PROCESSING, NOW.plusSeconds(2));

        mockMvc.perform(get("/api/v1/orders").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[1].status").value("PENDING"));

        mockMvc.perform(get("/api/v1/orders").param("status", "PROCESSING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/v1/orders").param("status", "SHIPPED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    @DisplayName("paging slices the list and reports accurate metadata across pages")
    void pagingSlicesTheList() throws Exception {
        for (int i = 0; i < 5; i++) {
            persistOrder(OrderStatus.PENDING, NOW.plusSeconds(i));
        }

        mockMvc.perform(get("/api/v1/orders").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false));

        mockMvc.perform(get("/api/v1/orders").param("page", "2").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    @DisplayName("a page past the end returns 200 with empty content and the true total")
    void pageBeyondEndIsEmpty() throws Exception {
        persistOrder(OrderStatus.PENDING, NOW);

        mockMvc.perform(get("/api/v1/orders").param("page", "50").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("the default ordering puts the newest order first")
    void defaultOrderingIsNewestFirst() throws Exception {
        persistOrder(OrderStatus.PENDING, NOW, "CUST-OLDEST");
        persistOrder(OrderStatus.PENDING, NOW.plusSeconds(60), "CUST-NEWEST");
        persistOrder(OrderStatus.PENDING, NOW.plusSeconds(30), "CUST-MIDDLE");

        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sort").value("createdAt: DESC"))
                .andExpect(jsonPath("$.content[0].customerId").value("CUST-NEWEST"))
                .andExpect(jsonPath("$.content[1].customerId").value("CUST-MIDDLE"))
                .andExpect(jsonPath("$.content[2].customerId").value("CUST-OLDEST"));
    }

    @Test
    @DisplayName("sorting by totalAmount ascending is honoured end to end")
    void sortByTotalAmountAscending() throws Exception {
        persistOrder(OrderStatus.PENDING, NOW, "CUST-BIG", new BigDecimal("300.00"));
        persistOrder(OrderStatus.PENDING, NOW.plusSeconds(1), "CUST-SMALL", new BigDecimal("100.00"));

        mockMvc.perform(get("/api/v1/orders").param("sort", "totalAmount,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].customerId").value("CUST-SMALL"))
                .andExpect(jsonPath("$.content[1].customerId").value("CUST-BIG"));
    }

    @Test
    @DisplayName("invalid list parameters return 400 without touching the database")
    void invalidParametersReturnBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("status", "pending"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/orders").param("sort", "customerId,asc"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/orders").param("page", "-1"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/orders").param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    private void persistOrder(OrderStatus status, Instant createdAt) {
        persistOrder(status, createdAt, "CUST-1001", new BigDecimal("60.00"));
    }

    private void persistOrder(OrderStatus status, Instant createdAt, String customerId) {
        persistOrder(status, createdAt, customerId, new BigDecimal("60.00"));
    }

    private void persistOrder(OrderStatus status, Instant createdAt, String customerId, BigDecimal total) {
        Order order = Order.builder()
                .customerId(customerId)
                .status(status)
                .totalAmount(total)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
        order.addItem(OrderItem.builder()
                .productId("SKU-MOUSE")
                .productName("Wireless Mouse")
                .quantity(2)
                .unitPrice(new BigDecimal("25.00"))
                .lineTotal(new BigDecimal("50.00"))
                .build());
        orderRepository.saveAndFlush(order);
    }
}
