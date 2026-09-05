package com.vikaan.ordermanagementsystem.job;

import com.vikaan.ordermanagementsystem.entity.Order;
import com.vikaan.ordermanagementsystem.entity.OrderItem;
import com.vikaan.ordermanagementsystem.entity.OrderStatus;
import com.vikaan.ordermanagementsystem.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the job is actually wired to a trigger. A missing {@code @EnableScheduling} is a silent
 * failure: every other test in the suite still passes and nothing ever runs in production.
 *
 * <p>The interval is shortened to milliseconds so the test finishes quickly, and the context is
 * discarded afterwards — cached contexts outlive the class that created them, and a scheduler
 * still ticking every 50ms would promote rows underneath later tests.
 */
@SpringBootTest(properties = {
        "orders.scheduler.enabled=true",
        "orders.scheduler.promotion-rate-ms=50"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PendingOrderPromotionSchedulingTest {

    private static final Instant NOW = Instant.parse("2026-09-03T14:30:00Z");

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    @Autowired
    private PendingOrderPromotionJob promotionJob;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void resetDatabase() {
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("the promotion job is registered as a scheduled task")
    void jobIsScheduled() {
        assertThat(promotionJob).isNotNull();
        assertThat(scheduledTaskHolder.getScheduledTasks())
                .as("scheduled tasks registered in the context")
                .anySatisfy(task -> assertThat(task.getTask().getRunnable().toString())
                        .contains("promotePendingOrders"));
    }

    @Test
    @DisplayName("a pending order is promoted by the scheduler without anyone calling the service")
    void schedulerPromotesWithoutBeingCalled() throws InterruptedException {
        UUID orderId = persistPendingOrder();

        OrderStatus observed = awaitStatusChange(orderId);

        assertThat(observed)
                .as("status of order %s after waiting for a scheduler tick", orderId)
                .isEqualTo(OrderStatus.PROCESSING);
    }

    /**
     * Polls rather than sleeping for a fixed span, so the test costs one tick rather than a
     * worst-case wait, and still fails clearly if the scheduler never runs.
     */
    private OrderStatus awaitStatusChange(UUID orderId) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        OrderStatus status = OrderStatus.PENDING;

        while (Instant.now().isBefore(deadline)) {
            status = orderRepository.findById(orderId).orElseThrow().getStatus();
            if (status != OrderStatus.PENDING) {
                return status;
            }
            Thread.sleep(25);
        }
        return status;
    }

    private UUID persistPendingOrder() {
        Order order = Order.builder()
                .customerId("CUST-1001")
                .status(OrderStatus.PENDING)
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
        return orderRepository.saveAndFlush(order).getId();
    }
}
