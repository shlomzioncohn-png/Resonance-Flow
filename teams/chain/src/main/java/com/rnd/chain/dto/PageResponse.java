package com.rnd.chain.dto;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Generic wrapper for a page of results (offset-based: {@code page}/
 * {@code size} as the request, {@code totalElements}/{@code totalPages}
 * computed from the underlying collection). Not specific to dependency
 * history - reusable for any future paginated endpoint.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public PageResponse {
        Objects.requireNonNull(content, "content must not be null");
    }

    /** Maps {@code content} element-by-element, keeping page/size/totalElements/totalPages unchanged. */
    public <R> PageResponse<R> map(Function<T, R> mapper) {
        List<R> mapped = content.stream().map(mapper).collect(Collectors.toList());
        return new PageResponse<>(mapped, page, size, totalElements, totalPages);
    }
}
