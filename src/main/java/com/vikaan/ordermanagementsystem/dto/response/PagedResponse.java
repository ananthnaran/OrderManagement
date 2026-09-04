package com.vikaan.ordermanagementsystem.dto.response;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable pagination envelope. Spring's {@code PageImpl} is not designed to be serialized
 * directly and its JSON shape has changed between versions, so the API exposes this instead.
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        String sort
) {

    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast(),
                page.getSort().isSorted() ? page.getSort().toString() : "UNSORTED"
        );
    }
}
