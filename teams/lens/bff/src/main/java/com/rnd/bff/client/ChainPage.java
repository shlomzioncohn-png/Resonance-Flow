package com.rnd.bff.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Chain's PageResponse: { content, page, size, totalElements, totalPages }. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainPage<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
}
