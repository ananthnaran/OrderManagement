package com.vikaan.ordermanagementsystem.controller.support;

import com.vikaan.ordermanagementsystem.entity.OrderStatus;
import com.vikaan.ordermanagementsystem.exception.InvalidRequestException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parses and validates the list endpoint's query parameters.
 * <p>
 * Sort properties are checked against a whitelist because an unknown property handed to a
 * {@link Pageable} reaches Hibernate and surfaces as a 500; validating here turns it into a 400.
 */
public final class OrderQueryParams {

    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;

    private static final Set<String> SORTABLE_PROPERTIES =
            Set.of("createdAt", "updatedAt", "totalAmount", "status");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private OrderQueryParams() {
    }

    /**
     * @param status raw {@code status} query parameter, may be {@code null} for "all statuses"
     * @return the parsed status, or {@code null} when no filter was requested
     */
    public static OrderStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return OrderStatus.valueOf(status.trim());
        } catch (IllegalArgumentException ex) {
            throw new InvalidRequestException(
                    "Invalid status '%s'. Accepted values: %s".formatted(status, acceptedStatuses()));
        }
    }

    public static Pageable parsePageable(int page, int size, String sort) {
        if (page < 0) {
            throw new InvalidRequestException("page must be 0 or greater, was " + page);
        }
        if (size < 1) {
            throw new InvalidRequestException("size must be at least 1, was " + size);
        }
        return PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), parseSort(sort));
    }

    private static Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return DEFAULT_SORT;
        }

        String[] parts = sort.split(",");
        String property = parts[0].trim();
        if (!SORTABLE_PROPERTIES.contains(property)) {
            throw new InvalidRequestException(
                    "Cannot sort by '%s'. Sortable properties: %s".formatted(property, sortableProperties()));
        }

        if (parts.length == 1) {
            return Sort.by(Sort.Direction.ASC, property);
        }

        String rawDirection = parts[1].trim();
        Sort.Direction direction = Sort.Direction.fromOptionalString(rawDirection)
                .orElseThrow(() -> new InvalidRequestException(
                        "Invalid sort direction '%s'. Use 'asc' or 'desc'.".formatted(rawDirection)));
        return Sort.by(direction, property);
    }

    private static String acceptedStatuses() {
        return Arrays.stream(OrderStatus.values())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
    }

    private static String sortableProperties() {
        return SORTABLE_PROPERTIES.stream().sorted().collect(Collectors.joining(", "));
    }
}
