package com.vikaan.ordermanagementsystem.repository;

import com.vikaan.ordermanagementsystem.entity.Order;
import com.vikaan.ordermanagementsystem.entity.OrderItem;
import com.vikaan.ordermanagementsystem.entity.OrderStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class OrderRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-03T14:30:00Z");

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("saving an order cascades its items into order_items")
    void savingOrderCascadesItems() {
        Order order = orderWithTwoItems();

        Order saved = orderRepository.saveAndFlush(order);
        entityManager.clear();

        Order reloaded = orderRepository.findWithItemsById(saved.getId()).orElseThrow();
        assertThat(reloaded.getId()).isNotNull();
        assertThat(reloaded.getItems()).hasSize(2);
        assertThat(reloaded.getItems())
                .extracting(OrderItem::getProductId)
                .containsExactlyInAnyOrder("SKU-MOUSE", "SKU-PAD");
    }

    @Test
    @DisplayName("a persisted order keeps its status, money scale and timestamps")
    void persistedOrderKeepsFields() {
        Order saved = orderRepository.saveAndFlush(orderWithTwoItems());
        entityManager.clear();

        Order reloaded = orderRepository.findWithItemsById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(reloaded.getTotalAmount()).isEqualByComparingTo("60.00");
        assertThat(reloaded.getCustomerId()).isEqualTo("CUST-1001");
        assertThat(reloaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(reloaded.getCancelledAt()).isNull();
        assertThat(reloaded.getVersion()).isNotNull();
    }

    @Test
    @DisplayName("findWithItemsById returns empty for an unknown id")
    void findReturnsEmptyForUnknownId() {
        Optional<Order> found = orderRepository.findWithItemsById(UUID.randomUUID());

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("removing an item from the order deletes the row via orphanRemoval")
    void orphanRemovalDeletesItems() {
        Order saved = orderRepository.saveAndFlush(orderWithTwoItems());

        saved.getItems().removeFirst();
        orderRepository.saveAndFlush(saved);
        entityManager.clear();

        Order reloaded = orderRepository.findWithItemsById(saved.getId()).orElseThrow();
        assertThat(reloaded.getItems()).hasSize(1);
    }

    @Test
    @DisplayName("findByStatus returns only orders in that status")
    void findByStatusFiltersByStatus() {
        persistOrder(OrderStatus.PENDING, NOW);
        persistOrder(OrderStatus.PENDING, NOW.plusSeconds(1));
        persistOrder(OrderStatus.PROCESSING, NOW.plusSeconds(2));
        persistOrder(OrderStatus.CANCELLED, NOW.plusSeconds(3));

        Page<Order> pending = orderRepository.findByStatus(OrderStatus.PENDING, PageRequest.of(0, 10));

        assertThat(pending.getTotalElements()).isEqualTo(2);
        assertThat(pending.getContent()).allMatch(o -> o.getStatus() == OrderStatus.PENDING);
    }

    @Test
    @DisplayName("findByStatus returns an empty page when nothing matches")
    void findByStatusReturnsEmptyPage() {
        persistOrder(OrderStatus.PENDING, NOW);

        Page<Order> shipped = orderRepository.findByStatus(OrderStatus.SHIPPED, PageRequest.of(0, 10));

        assertThat(shipped.getContent()).isEmpty();
        assertThat(shipped.getTotalElements()).isZero();
        assertThat(shipped.getTotalPages()).isZero();
    }

    @Test
    @DisplayName("paging slices the result set and reports correct metadata")
    void pagingSlicesResults() {
        for (int i = 0; i < 5; i++) {
            persistOrder(OrderStatus.PENDING, NOW.plusSeconds(i));
        }

        Page<Order> firstPage = orderRepository.findAll(PageRequest.of(0, 2));

        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(5);
        assertThat(firstPage.getTotalPages()).isEqualTo(3);
        assertThat(firstPage.isFirst()).isTrue();
        assertThat(firstPage.isLast()).isFalse();

        Page<Order> lastPage = orderRepository.findAll(PageRequest.of(2, 2));

        assertThat(lastPage.getContent()).hasSize(1);
        assertThat(lastPage.isLast()).isTrue();
    }

    @Test
    @DisplayName("a page past the end is empty but still reports the true total")
    void pageBeyondEndIsEmpty() {
        persistOrder(OrderStatus.PENDING, NOW);

        Page<Order> page = orderRepository.findAll(PageRequest.of(50, 20));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("sorting by createdAt descending returns the newest order first")
    void sortsByCreatedAtDescending() {
        persistOrder(OrderStatus.PENDING, NOW);
        Order newest = persistOrder(OrderStatus.PENDING, NOW.plusSeconds(60));
        persistOrder(OrderStatus.PENDING, NOW.plusSeconds(30));

        Page<Order> page = orderRepository.findAll(
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page.getContent().getFirst().getId()).isEqualTo(newest.getId());
    }

    @Test
    @DisplayName("sorting by totalAmount ascending orders by money value")
    void sortsByTotalAmountAscending() {
        persistOrder(OrderStatus.PENDING, NOW, new BigDecimal("300.00"));
        persistOrder(OrderStatus.PENDING, NOW.plusSeconds(1), new BigDecimal("100.00"));
        persistOrder(OrderStatus.PENDING, NOW.plusSeconds(2), new BigDecimal("200.00"));

        Page<Order> page = orderRepository.findAll(
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "totalAmount")));

        assertThat(page.getContent())
                .extracting(Order::getTotalAmount)
                .containsExactly(
                        new BigDecimal("100.00"),
                        new BigDecimal("200.00"),
                        new BigDecimal("300.00"));
    }

    @Test
    @DisplayName("the declared indexes are actually created in the schema")
    void declaredIndexesExist() {
        @SuppressWarnings("unchecked")
        var indexNames = (java.util.List<String>) entityManager
                .createNativeQuery("SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES")
                .getResultList()
                .stream()
                .map(row -> String.valueOf(row).toLowerCase())
                .toList();

        assertThat(indexNames).contains(
                "idx_orders_status",
                "idx_orders_created_at",
                "idx_orders_status_created_at",
                "idx_order_items_order_id");
    }

    @Test
    @DisplayName("the conditional cancel updates a PENDING row and reports one affected row")
    void conditionalCancelUpdatesPendingRow() {
        Order pending = persistOrder(OrderStatus.PENDING, NOW);
        Instant cancelledAt = NOW.plusSeconds(120);

        int rows = orderRepository.cancelIfInStatus(pending.getId(), OrderStatus.PENDING, cancelledAt);

        assertThat(rows).isEqualTo(1);

        Order reloaded = orderRepository.findWithItemsById(pending.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reloaded.getCancelledAt()).isEqualTo(cancelledAt);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(cancelledAt);
        assertThat(reloaded.getVersion()).isEqualTo(pending.getVersion() + 1);
    }

    /**
     * The heart of the cancel rule: it is the SQL predicate that refuses, not an in-memory check,
     * so a concurrent promotion cannot slip between a read and a write.
     */
    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PROCESSING", "SHIPPED", "DELIVERED", "CANCELLED"})
    @DisplayName("the conditional cancel reports zero rows and changes nothing once the order has left PENDING")
    void conditionalCancelSkipsNonPendingRows(OrderStatus status) {
        Order order = persistOrder(status, NOW);

        int rows = orderRepository.cancelIfInStatus(order.getId(), OrderStatus.PENDING, NOW.plusSeconds(120));

        assertThat(rows).isZero();

        Order reloaded = orderRepository.findWithItemsById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(status);
        assertThat(reloaded.getCancelledAt()).isNull();
        assertThat(reloaded.getUpdatedAt()).isEqualTo(NOW);
        assertThat(reloaded.getVersion()).isEqualTo(order.getVersion());
    }

    @Test
    @DisplayName("the conditional cancel reports zero rows for an unknown id")
    void conditionalCancelReportsZeroForUnknownId() {
        int rows = orderRepository.cancelIfInStatus(UUID.randomUUID(), OrderStatus.PENDING, NOW);

        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("the conditional cancel leaves other pending orders alone")
    void conditionalCancelTouchesOnlyTheTargetRow() {
        Order target = persistOrder(OrderStatus.PENDING, NOW);
        Order bystander = persistOrder(OrderStatus.PENDING, NOW.plusSeconds(1));

        orderRepository.cancelIfInStatus(target.getId(), OrderStatus.PENDING, NOW.plusSeconds(120));

        Order reloaded = orderRepository.findWithItemsById(bystander.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(reloaded.getCancelledAt()).isNull();
    }

    @Test
    @DisplayName("the bulk promotion moves every pending order and reports the count")
    void bulkPromotionMovesPendingOrders() {
        persistOrder(OrderStatus.PENDING, NOW);
        persistOrder(OrderStatus.PENDING, NOW.plusSeconds(1));

        int promoted = orderRepository.promoteAllPending(NOW.plusSeconds(60));

        assertThat(promoted).isEqualTo(2);
        assertThat(orderRepository.findByStatus(OrderStatus.PROCESSING, PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PROCESSING", "SHIPPED", "DELIVERED", "CANCELLED"})
    @DisplayName("the bulk promotion never touches an order that is not pending")
    void bulkPromotionLeavesNonPendingOrdersAlone(OrderStatus status) {
        Order order = persistOrder(status, NOW);

        int promoted = orderRepository.promoteAllPending(NOW.plusSeconds(60));

        assertThat(promoted).isZero();

        Order reloaded = orderRepository.findWithItemsById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(status);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(NOW);
        assertThat(reloaded.getVersion()).isEqualTo(order.getVersion());
    }

    @Test
    @DisplayName("the bulk promotion advances updatedAt and the version of the rows it moves")
    void bulkPromotionAdvancesVersionAndTimestamp() {
        Order pending = persistOrder(OrderStatus.PENDING, NOW);
        Instant promotedAt = NOW.plusSeconds(60);

        orderRepository.promoteAllPending(promotedAt);

        Order reloaded = orderRepository.findWithItemsById(pending.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PROCESSING);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(promotedAt);
        assertThat(reloaded.getCancelledAt()).isNull();
        assertThat(reloaded.getVersion()).isEqualTo(pending.getVersion() + 1);
    }

    @Test
    @DisplayName("the bulk promotion is a no-op when nothing is pending")
    void bulkPromotionWithNothingPendingIsNoOp() {
        persistOrder(OrderStatus.DELIVERED, NOW);

        assertThat(orderRepository.promoteAllPending(NOW.plusSeconds(60))).isZero();
    }

    /**
     * The two statements meeting on one row, in the order the scheduler is most likely to lose:
     * cancel commits first, and the promotion's own WHERE clause is what protects the result.
     */
    @Test
    @DisplayName("an order cancelled before a promotion stays cancelled")
    void cancelBeforePromotionSurvives() {
        Order order = persistOrder(OrderStatus.PENDING, NOW);

        int cancelled = orderRepository.cancelIfInStatus(order.getId(), OrderStatus.PENDING, NOW.plusSeconds(30));
        int promoted = orderRepository.promoteAllPending(NOW.plusSeconds(60));

        assertThat(cancelled).isEqualTo(1);
        assertThat(promoted).isZero();

        Order reloaded = orderRepository.findWithItemsById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reloaded.getCancelledAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    @DisplayName("an order promoted before a cancel can no longer be cancelled")
    void promotionBeforeCancelWins() {
        Order order = persistOrder(OrderStatus.PENDING, NOW);

        int promoted = orderRepository.promoteAllPending(NOW.plusSeconds(30));
        int cancelled = orderRepository.cancelIfInStatus(order.getId(), OrderStatus.PENDING, NOW.plusSeconds(60));

        assertThat(promoted).isEqualTo(1);
        assertThat(cancelled).isZero();

        Order reloaded = orderRepository.findWithItemsById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PROCESSING);
        assertThat(reloaded.getCancelledAt()).isNull();
    }

    private Order persistOrder(OrderStatus status, Instant createdAt) {
        return persistOrder(status, createdAt, new BigDecimal("60.00"));
    }

    private Order persistOrder(OrderStatus status, Instant createdAt, BigDecimal total) {
        Order order = Order.builder()
                .customerId("CUST-1001")
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
        return orderRepository.saveAndFlush(order);
    }

    private Order orderWithTwoItems() {
        Order order = Order.builder()
                .customerId("CUST-1001")
                .status(OrderStatus.PENDING)
                .totalAmount(new BigDecimal("60.00"))
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
        order.addItem(OrderItem.builder()
                .productId("SKU-PAD")
                .productName("Mouse Pad")
                .quantity(1)
                .unitPrice(new BigDecimal("10.00"))
                .lineTotal(new BigDecimal("10.00"))
                .build());

        return order;
    }
}
