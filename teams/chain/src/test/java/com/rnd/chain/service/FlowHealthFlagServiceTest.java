package com.rnd.chain.service;

import com.rnd.chain.dto.FlowHealthFlag;
import com.rnd.chain.dto.FlowHealthPattern;
import com.rnd.chain.dto.FlowHealthSeverity;
import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.repository.FlowHealthFlagRepository;
import com.rnd.chain.repository.TransactionRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FlowHealthFlagServiceTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");

    private final PageResponse<FlowHealthFlag> emptyPage = new PageResponse<>(List.of(), 0, 20, 0, 0);

    private FlowHealthFlagRepository repository;
    private TransactionRunner transactionRunner;
    private FlowHealthFlagService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(FlowHealthFlagRepository.class);
        transactionRunner = mock(TransactionRunner.class);
        when(transactionRunner.runInReadOnlyTransaction(any())).thenAnswer(
                invocation -> ((Supplier<Object>) invocation.getArgument(0)).get());
        service = new FlowHealthFlagService(repository, transactionRunner);
    }

    @Test
    void getFlags_delegatesTenantFiltersAndPagingInAReadOnlyTransaction() {
        when(repository.findPage(TENANT, FlowHealthPattern.SLOW_CHAIN, FlowHealthSeverity.HIGH, 2, 5))
                .thenReturn(emptyPage);

        PageResponse<FlowHealthFlag> result =
                service.getFlags(TENANT, FlowHealthPattern.SLOW_CHAIN, FlowHealthSeverity.HIGH, 2, 5);

        assertThat(result).isSameAs(emptyPage);
        verify(transactionRunner).runInReadOnlyTransaction(any());
    }

    @Test
    void getFlags_nullFilters_meanAny() {
        when(repository.findPage(TENANT, null, null, 0, 20)).thenReturn(emptyPage);

        assertThat(service.getFlags(TENANT, null, null, 0, 20)).isSameAs(emptyPage);
    }

    @Test
    void getFlags_asksTheStoreForTheCallersTenantOnly() {
        when(repository.findPage(TENANT, null, null, 0, 20)).thenReturn(emptyPage);

        service.getFlags(TENANT, null, null, 0, 20);

        verify(repository).findPage(TENANT, null, null, 0, 20);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .findPage(org.mockito.ArgumentMatchers.eq(OTHER_TENANT), any(), any(),
                        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void getFlags_maxPageSizeIsAccepted() {
        when(repository.findPage(TENANT, null, null, 0, 100)).thenReturn(emptyPage);

        assertThat(service.getFlags(TENANT, null, null, 0, 100)).isSameAs(emptyPage);
    }

    @Test
    void getFlags_noTenant_isRejectedBeforeTouchingTheStore() {
        assertThatThrownBy(() -> service.getFlags(null, null, null, 0, 20))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(repository, transactionRunner);
    }

    @Test
    void getFlags_negativePage_isRejectedBeforeTouchingTheStore() {
        assertThatThrownBy(() -> service.getFlags(TENANT, null, null, -1, 20))
                .isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(repository, transactionRunner);
    }

    @Test
    void getFlags_sizeOutOfRange_isRejected() {
        assertThatThrownBy(() -> service.getFlags(TENANT, null, null, 0, 0))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.getFlags(TENANT, null, null, 0, 101))
                .isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(repository);
    }
}
