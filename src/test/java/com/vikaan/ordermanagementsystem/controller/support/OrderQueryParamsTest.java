package com.vikaan.ordermanagementsystem.controller.support;

import com.vikaan.ordermanagementsystem.entity.OrderStatus;
import com.vikaan.ordermanagementsystem.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderQueryParamsTest {

    @Test
    @DisplayName("a missing status parameter means no filter rather than an error")
    void missingStatusMeansNoFilter() {
        assertThat(OrderQueryParams.parseStatus(null)).isNull();
        assertThat(OrderQueryParams.parseStatus("  ")).isNull();
    }

    @Test
    @DisplayName("every valid status name is accepted")
    void validStatusIsParsed() {
        assertThat(OrderQueryParams.parseStatus("PENDING")).isEqualTo(OrderStatus.PENDING);
        assertThat(OrderQueryParams.parseStatus("CANCELLED")).isEqualTo(OrderStatus.CANCELLED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "Pending", "FOO", "PENDING_"})
    @DisplayName("an unknown or wrongly cased status is rejected with the accepted values listed")
    void invalidStatusIsRejected(String status) {
        assertThatThrownBy(() -> OrderQueryParams.parseStatus(status))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Accepted values")
                .hasMessageContaining("PENDING")
                .hasMessageContaining("CANCELLED");
    }

    @Test
    @DisplayName("default paging is 20 rows sorted by createdAt descending")
    void defaultPagingIsNewestFirst() {
        Pageable pageable = OrderQueryParams.parsePageable(0, 20, "createdAt,desc");

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("a blank sort parameter falls back to createdAt descending")
    void blankSortFallsBackToDefault() {
        Pageable pageable = OrderQueryParams.parsePageable(0, 20, "  ");

        assertThat(pageable.getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("an omitted sort direction defaults to ascending")
    void omittedDirectionDefaultsToAscending() {
        Pageable pageable = OrderQueryParams.parsePageable(0, 20, "totalAmount");

        assertThat(pageable.getSort().getOrderFor("totalAmount").getDirection())
                .isEqualTo(Sort.Direction.ASC);
    }

    @ParameterizedTest
    @ValueSource(strings = {"createdAt", "updatedAt", "totalAmount", "status"})
    @DisplayName("every whitelisted property can be sorted on")
    void whitelistedPropertiesAreAccepted(String property) {
        Pageable pageable = OrderQueryParams.parsePageable(0, 20, property + ",asc");

        assertThat(pageable.getSort().getOrderFor(property)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "customerId", "version", "dropTable", "items"})
    @DisplayName("a non-whitelisted sort property is rejected instead of reaching Hibernate")
    void unknownSortPropertyIsRejected(String property) {
        assertThatThrownBy(() -> OrderQueryParams.parsePageable(0, 20, property + ",asc"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Cannot sort by '" + property + "'")
                .hasMessageContaining("Sortable properties");
    }

    @Test
    @DisplayName("an unusable sort direction is rejected")
    void invalidSortDirectionIsRejected() {
        assertThatThrownBy(() -> OrderQueryParams.parsePageable(0, 20, "createdAt,sideways"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Invalid sort direction");
    }

    @Test
    @DisplayName("a negative page is rejected")
    void negativePageIsRejected() {
        assertThatThrownBy(() -> OrderQueryParams.parsePageable(-1, 20, "createdAt,desc"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("page must be 0 or greater");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5})
    @DisplayName("a page size below one is rejected")
    void nonPositiveSizeIsRejected(int size) {
        assertThatThrownBy(() -> OrderQueryParams.parsePageable(0, size, "createdAt,desc"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("size must be at least 1");
    }

    @Test
    @DisplayName("an oversized page size is clamped rather than rejected")
    void oversizedPageIsClamped() {
        Pageable pageable = OrderQueryParams.parsePageable(0, 5_000, "createdAt,desc");

        assertThat(pageable.getPageSize()).isEqualTo(OrderQueryParams.MAX_PAGE_SIZE);
    }
}
