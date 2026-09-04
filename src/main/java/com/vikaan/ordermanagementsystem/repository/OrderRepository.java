package com.vikaan.ordermanagementsystem.repository;

import com.vikaan.ordermanagementsystem.entity.Order;
import com.vikaan.ordermanagementsystem.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(UUID id);

    /**
     * Deliberately no fetch join on {@code items}: combining a collection fetch with a
     * {@link Pageable} forces Hibernate to paginate in memory. {@code items} is batched
     * via {@code @BatchSize} on the association instead.
     */
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);
}
