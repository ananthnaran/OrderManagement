package com.vikaan.ordermanagementsystem.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable pagination envelope. Spring's {@code PageImpl} is not designed to be serialized
 * directly and its JSON shape has changed between versions, so the API exposes this instead.
 */
@Schema(description = "One page of results, plus the paging metadata needed to walk the rest")
public record PagedResponse<T>(

        @Schema(description = "The results on this page. Empty when the filter matches nothing.")
        List<T> content,

        @Schema(description = "Zero-based index of this page", example = "0")
        int page,

        @Schema(description = "Requested page size, after clamping", example = "20")
        int size,

        @Schema(description = "Total matching results across every page", example = "3")
        long totalElements,

        @Schema(description = "Total number of pages, or 0 when nothing matched", example = "1")
        int totalPages,

        @Schema(description = "Whether this is the first page", example = "true")
        boolean first,

        @Schema(description = "Whether this is the last page", example = "true")
        boolean last,

        @Schema(description = "The sort that was applied, or `UNSORTED`", example = "createdAt: DESC")
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
