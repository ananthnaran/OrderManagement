package com.vikaan.ordermanagementsystem.exception;

import com.vikaan.ordermanagementsystem.entity.OrderStatus;

/**
 * Thrown when an order exists but is in the wrong status for the requested transition.
 * Distinct from {@link OrderNotFoundException} so the two never collapse into one response code.
 */
public class InvalidOrderStateException extends RuntimeException {

    private InvalidOrderStateException(String message) {
        super(message);
    }

    public static InvalidOrderStateException cannotCancel(OrderStatus currentStatus) {
        return new InvalidOrderStateException(
                "Order can be cancelled only while PENDING. Current status: " + currentStatus);
    }
}
