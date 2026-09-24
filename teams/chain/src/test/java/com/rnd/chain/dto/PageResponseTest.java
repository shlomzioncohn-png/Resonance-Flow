package com.rnd.chain.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResponseTest {

    @Test
    void map_transformsContent_keepsPagingFieldsUnchanged() {
        PageResponse<Integer> page = new PageResponse<>(List.of(1, 2, 3), 0, 20, 3, 1);

        PageResponse<String> mapped = page.map(i -> "v" + i);

        assertThat(mapped.content()).containsExactly("v1", "v2", "v3");
        assertThat(mapped.page()).isEqualTo(0);
        assertThat(mapped.size()).isEqualTo(20);
        assertThat(mapped.totalElements()).isEqualTo(3);
        assertThat(mapped.totalPages()).isEqualTo(1);
    }

    @Test
    void map_onEmptyContent_producesEmptyContent() {
        PageResponse<Integer> page = new PageResponse<>(List.of(), 0, 20, 0, 0);

        PageResponse<String> mapped = page.map(i -> "v" + i);

        assertThat(mapped.content()).isEmpty();
    }
}
