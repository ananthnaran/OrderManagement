package com.vikaan.ordermanagementsystem.repository;

import com.vikaan.ordermanagementsystem.entity.Order;
import com.vikaan.ordermanagementsystem.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    /**
     * Cancels {@code id} only while it still holds {@code expectedStatus}, returning the number of
     * rows that matched. The row count, rather than a preceding read, is what decides the outcome:
     * a read-then-write would let the promotion job and a concurrent cancel overwrite each other.
     *
     * <p>Bulk JPQL bypasses the persistence context, {@code @Version} checking and entity
     * callbacks, so {@code version} and {@code updatedAt} are advanced inside the statement, and
     * the context is flushed beforehand and cleared afterwards to keep stale copies out of the
     * remainder of the transaction.
     *
     * @return {@code 1} when the order was cancelled, {@code 0} when it does not exist or has
     *         already left {@code expectedStatus}
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Order o
               set o.status = com.vikaan.ordermanagementsystem.entity.OrderStatus.CANCELLED,
                   o.cancelledAt = :now,
                   o.updatedAt = :now,
                   o.version = o.version + 1
             where o.id = :id
               and o.status = :expectedStatus
            """)
    int cancelIfInStatus(@Param("id") UUID id,
                         @Param("expectedStatus") OrderStatus expectedStatus,
                         @Param("now") Instant now);

    /**
     * Promotes every {@code PENDING} order to {@code PROCESSING} in a single statement, and
     * returns how many moved.
     *
     * <p>One statement rather than a loop over loaded entities: a per-row read-modify-write would
     * reopen the window in which a concurrent cancel is overwritten. The {@code PENDING} predicate
     * is also what makes cancellation safe in the other direction — a row that reached
     * {@code CANCELLED} first simply no longer matches, so it can never be resurrected.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Order o
               set o.status = com.vikaan.ordermanagementsystem.entity.OrderStatus.PROCESSING,
                   o.updatedAt = :now,
                   o.version = o.version + 1
             where o.status = com.vikaan.ordermanagementsystem.entity.OrderStatus.PENDING
            """)
    int promoteAllPending(@Param("now") Instant now);
}
