package com.vikaan.ordermanagementsystem.service;

import com.vikaan.ordermanagementsystem.dto.request.CreateOrderRequest;
import com.vikaan.ordermanagementsystem.dto.request.OrderItemRequest;
import com.vikaan.ordermanagementsystem.dto.response.OrderResponse;
import com.vikaan.ordermanagementsystem.dto.response.PagedResponse;
import com.vikaan.ordermanagementsystem.entity.Order;
import com.vikaan.ordermanagementsystem.entity.OrderStatus;
import com.vikaan.ordermanagementsystem.exception.InvalidRequestException;
import com.vikaan.ordermanagementsystem.exception.OrderNotFoundException;
import com.vikaan.ordermanagementsystem.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-03T14:30:00Z");

    private OrderRepository orderRepository;
    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        orderService = new OrderServiceImpl(orderRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(UUID.randomUUID());
            return order;
        });
    }

    @Test
    @DisplayName("create computes line totals and the order total from quantity and unit price")
    void createComputesTotals() {
        CreateOrderRequest request = new CreateOrderRequest("CUST-1001", List.of(
                new OrderItemRequest("SKU-MOUSE", "Wireless Mouse", 2, new BigDecimal("25.00")),
                new OrderItemRequest("SKU-PAD", "Mouse Pad", 1, new BigDecimal("10.00"))
        ));

        OrderResponse response = orderService.createOrder(request);

        assertThat(response.totalAmount()).isEqualByComparingTo("60.00");
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).lineTotal()).isEqualByComparingTo("50.00");
        assertThat(response.items().get(1).lineTotal()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("create always starts a new order as PENDING with server-side timestamps")
    void createStartsAsPending() {
        OrderResponse response = orderService.createOrder(singleItemRequest());

        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.createdAt()).isEqualTo(NOW);
        assertThat(response.updatedAt()).isEqualTo(NOW);
        assertThat(response.cancelledAt()).isNull();
        assertThat(response.id()).isNotNull();
    }

    @Test
    @DisplayName("create links every item back to its parent order so the cascade persists them")
    void createLinksItemsToOrder() {
        orderService.createOrder(singleItemRequest());

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        Order saved = captor.getValue();

        assertThat(saved.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(saved.getItems()).allSatisfy(item -> assertThat(item.getOrder()).isSameAs(saved));
    }

    @Test
    @DisplayName("create rounds money to two decimal places using HALF_UP")
    void createRoundsMoney() {
        CreateOrderRequest request = new CreateOrderRequest("CUST-1001", List.of(
                new OrderItemRequest("SKU-A", "Item A", 3, new BigDecimal("19.99"))
        ));

        OrderResponse response = orderService.createOrder(request);

        assertThat(response.totalAmount()).isEqualByComparingTo("59.97");
        assertThat(response.totalAmount().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("create truncates timestamps to microseconds so they survive a TIMESTAMP(6) round-trip")
    void createTruncatesTimestampsToMicros() {
        Instant withNanos = Instant.parse("2026-09-03T14:30:00.088344900Z");
        OrderServiceImpl service = new OrderServiceImpl(orderRepository, Clock.fixed(withNanos, ZoneOffset.UTC));

        OrderResponse response = service.createOrder(singleItemRequest());

        assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-09-03T14:30:00.088344Z"));
        assertThat(response.createdAt().getNano() % 1000).isZero();
    }

    @Test
    @DisplayName("create rejects the whole request when the same productId appears twice")
    void createRejectsDuplicateProducts() {
        CreateOrderRequest request = new CreateOrderRequest("CUST-1001", List.of(
                new OrderItemRequest("SKU-DUP", "Item", 1, new BigDecimal("5.00")),
                new OrderItemRequest("SKU-DUP", "Item again", 2, new BigDecimal("5.00"))
        ));

        assertThatThrownBy(() -> orderService.createOrder(request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("SKU-DUP");

        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("get returns the stored order when the id exists")
    void getReturnsOrder() {
        UUID id = UUID.randomUUID();
        Order stored = Order.builder()
                .id(id)
                .customerId("CUST-1001")
                .status(OrderStatus.PENDING)
                .totalAmount(new BigDecimal("60.00"))
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
        when(orderRepository.findWithItemsById(id)).thenReturn(Optional.of(stored));

        OrderResponse response = orderService.getOrderById(id);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.customerId()).isEqualTo("CUST-1001");
        assertThat(response.items()).isEmpty();
    }

    @Test
    @DisplayName("get throws OrderNotFoundException for an unknown id")
    void getThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findWithItemsById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrderById(id))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    @DisplayName("list without a status filter reads every order")
    void listWithoutFilterReadsAllOrders() {
        Pageable pageable = PageRequest.of(0, 20);
        when(orderRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(storedOrder()), pageable, 1));

        PagedResponse<OrderResponse> response = orderService.listOrders(null, pageable);

        assertThat(response.content()).hasSize(1);
        assertThat(response.totalElements()).isEqualTo(1);
        verify(orderRepository).findAll(pageable);
        verify(orderRepository, never()).findByStatus(any(), any());
    }

    @Test
    @DisplayName("list with a status filter uses the filtered query instead")
    void listWithFilterUsesFilteredQuery() {
        Pageable pageable = PageRequest.of(0, 20);
        when(orderRepository.findByStatus(OrderStatus.PENDING, pageable))
                .thenReturn(new PageImpl<>(List.of(storedOrder()), pageable, 1));

        PagedResponse<OrderResponse> response = orderService.listOrders(OrderStatus.PENDING, pageable);

        assertThat(response.content()).hasSize(1);
        verify(orderRepository).findByStatus(OrderStatus.PENDING, pageable);
        verify(orderRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    @DisplayName("list carries the pagination metadata into the envelope")
    void listCarriesPaginationMetadata() {
        Pageable pageable = PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "createdAt"));
        when(orderRepository.findAll(pageable))
                .thenReturn(new PageImpl<>(List.of(storedOrder(), storedOrder()), pageable, 5));

        PagedResponse<OrderResponse> response = orderService.listOrders(null, pageable);

        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(5);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.first()).isFalse();
        assertThat(response.last()).isFalse();
        assertThat(response.sort()).isEqualTo("createdAt: DESC");
    }

    @Test
    @DisplayName("list returns an empty envelope rather than failing when nothing matches")
    void listReturnsEmptyEnvelope() {
        Pageable pageable = PageRequest.of(0, 20);
        when(orderRepository.findByStatus(OrderStatus.SHIPPED, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        PagedResponse<OrderResponse> response = orderService.listOrders(OrderStatus.SHIPPED, pageable);

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
    }

    private Order storedOrder() {
        return Order.builder()
                .id(UUID.randomUUID())
                .customerId("CUST-1001")
                .status(OrderStatus.PENDING)
                .totalAmount(new BigDecimal("60.00"))
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private CreateOrderRequest singleItemRequest() {
        return new CreateOrderRequest("CUST-1001", List.of(
                new OrderItemRequest("SKU-MOUSE", "Wireless Mouse", 2, new BigDecimal("25.00"))
        ));
    }
}
