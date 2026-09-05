package com.vikaan.ordermanagementsystem.job;

import com.vikaan.ordermanagementsystem.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingOrderPromotionJobTest {

    private final OrderService orderService = mock(OrderService.class);
    private final PendingOrderPromotionJob job = new PendingOrderPromotionJob(orderService);

    @Test
    @DisplayName("a tick delegates the rule to the service rather than reimplementing it")
    void tickDelegatesToTheService() {
        when(orderService.promotePendingOrders()).thenReturn(4);

        job.promotePendingOrders();

        verify(orderService).promotePendingOrders();
    }

    /**
     * An exception escaping a {@code @Scheduled} method cancels every future execution of that
     * task, so one failed tick would stop promotion permanently while the app looks healthy.
     */
    @Test
    @DisplayName("a failing tick is contained so the schedule survives to the next one")
    void failingTickDoesNotEscape() {
        when(orderService.promotePendingOrders()).thenThrow(new RuntimeException("database is down"));

        assertThatCode(job::promotePendingOrders).doesNotThrowAnyException();

        verify(orderService).promotePendingOrders();
    }

    @Test
    @DisplayName("a tick with nothing pending completes quietly")
    void emptyTickIsQuiet() {
        when(orderService.promotePendingOrders()).thenReturn(0);

        assertThatCode(job::promotePendingOrders).doesNotThrowAnyException();
    }
}
