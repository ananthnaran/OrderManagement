package com.vikaan.ordermanagementsystem.job;

import com.vikaan.ordermanagementsystem.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Moves {@code PENDING} orders to {@code PROCESSING} on a fixed interval.
 *
 * <p>The bean is only a trigger. The rule itself lives in
 * {@link OrderService#promotePendingOrders()} so tests can exercise it directly instead of
 * waiting out an interval.
 *
 * <p>Disabled with {@code orders.scheduler.enabled=false}, which the test suite does: a tick
 * firing part-way through a test would flip orders another test is asserting on.
 */
@Component
@ConditionalOnProperty(name = "orders.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class PendingOrderPromotionJob {

    private static final Logger log = LoggerFactory.getLogger(PendingOrderPromotionJob.class);

    private final OrderService orderService;

    /**
     * {@code fixedRate} rather than {@code fixedDelay}, because the requirement is "every minute"
     * regardless of how long a tick takes. Spring's scheduler is single-threaded by default, so a
     * slow tick delays the next one instead of overlapping with it.
     */
    @Scheduled(fixedRateString = "${orders.scheduler.promotion-rate-ms}")
    public void promotePendingOrders() {
        try {
            int promoted = orderService.promotePendingOrders();
            if (promoted == 0) {
                log.debug("Promotion tick: no pending orders");
            }
        } catch (Exception ex) {
            // An exception escaping a @Scheduled method cancels all of its future executions, so
            // one bad tick would silently stop promotion for the life of the process.
            log.error("Promotion tick failed; the next tick will retry", ex);
        }
    }
}
