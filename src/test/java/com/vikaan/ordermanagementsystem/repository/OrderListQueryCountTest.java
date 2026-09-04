package com.vikaan.ordermanagementsystem.repository;

import com.vikaan.ordermanagementsystem.entity.Order;
import com.vikaan.ordermanagementsystem.entity.OrderItem;
import com.vikaan.ordermanagementsystem.entity.OrderStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the fix for the N+1 problem on {@code Order.items}: listing orders must not issue
 * one extra query per order. {@code @BatchSize} on the association collapses the item loads
 * into a single batched select.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class OrderListQueryCountTest {

    private static final Instant NOW = Instant.parse("2026-09-03T14:30:00Z");
    private static final int ORDER_COUNT = 5;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName("listing orders and reading their items stays a small constant number of queries")
    void listingDoesNotIssueOneQueryPerOrder() {
        for (int i = 0; i < ORDER_COUNT; i++) {
            persistOrder(NOW.plusSeconds(i));
        }
        entityManager.flush();
        entityManager.clear();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        Page<Order> page = orderRepository.findAll(PageRequest.of(0, ORDER_COUNT));
        int itemsSeen = page.getContent().stream()
                .mapToInt(order -> order.getItems().size())
                .sum();

        long queries = statistics.getPrepareStatementCount();

        assertThat(page.getContent()).hasSize(ORDER_COUNT);
        assertThat(itemsSeen).isEqualTo(ORDER_COUNT * 2);

        // Measured: 3 queries with @BatchSize (count, orders, one batched item select).
        // Removing @BatchSize makes it 7 for 5 orders, and this assertion fails.
        assertThat(queries)
                .as("queries issued while listing %d orders and touching their items", ORDER_COUNT)
                .isLessThan(ORDER_COUNT);
    }

    private void persistOrder(Instant createdAt) {
        Order order = Order.builder()
                .customerId("CUST-1001")
                .status(OrderStatus.PENDING)
                .totalAmount(new BigDecimal("60.00"))
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
        order.addItem(OrderItem.builder()
                .productId("SKU-PAD")
                .productName("Mouse Pad")
                .quantity(1)
                .unitPrice(new BigDecimal("10.00"))
                .lineTotal(new BigDecimal("10.00"))
                .build());
        orderRepository.save(order);
    }
}
