package com.rnd.chain.service;

import com.rnd.chain.dto.ConstraintHistoryEntry;
import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.ConstraintHistoryRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.repository.TransactionRunner;
import com.rnd.chain.testsupport.TaskFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConstraintHistoryServiceTest {

    private static final Instant T1 = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-02T10:00:00Z");

    private final UUID taskId = UUID.randomUUID();
    private final PageResponse<ConstraintHistoryEntry> emptyPage = new PageResponse<>(List.of(), 0, 20, 0, 0);

    private TaskRepository taskRepository;
    private ConstraintHistoryRepository historyRepository;
    private TransactionRunner transactionRunner;
    private ConstraintHistoryService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        historyRepository = mock(ConstraintHistoryRepository.class);
        transactionRunner = mock(TransactionRunner.class);
        when(transactionRunner.runInReadOnlyTransaction(any())).thenAnswer(
                invocation -> ((Supplier<Object>) invocation.getArgument(0)).get());
        when(historyRepository.findByTaskId(any(), any(), any(), anyInt(), anyInt())).thenReturn(emptyPage);
        service = new ConstraintHistoryService(taskRepository, historyRepository, transactionRunner);
    }

    @Test
    void getHistory_existingTask_delegatesWithRangeAndPaging() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(TaskFixtures.newTask(taskId)));

        PageResponse<ConstraintHistoryEntry> result = service.getHistory(taskId, T1, T2, 2, 5);

        assertThat(result).isSameAs(emptyPage);
        verify(historyRepository).findByTaskId(taskId, T1, T2, 2, 5);
        verify(historyRepository, never()).existsByTaskId(any());
    }

    @Test
    void getHistory_runsInsideAReadOnlyTransaction() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(TaskFixtures.newTask(taskId)));

        service.getHistory(taskId, null, null, 0, 20);

        verify(transactionRunner).runInReadOnlyTransaction(any());
    }

    @Test
    void getHistory_taskGoneButHistoryRemains_returnsTheHistory() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());
        when(historyRepository.existsByTaskId(taskId)).thenReturn(true);

        assertThat(service.getHistory(taskId, null, null, 0, 20)).isSameAs(emptyPage);
    }

    @Test
    void getHistory_noTaskAndNoHistory_throwsNotFound() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());
        when(historyRepository.existsByTaskId(taskId)).thenReturn(false);

        assertThatThrownBy(() -> service.getHistory(taskId, null, null, 0, 20))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void getHistory_fromAfterTo_throwsInvalidRequestBeforeAnyRead() {
        assertThatThrownBy(() -> service.getHistory(taskId, T2, T1, 0, 20))
                .isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(taskRepository, historyRepository, transactionRunner);
    }

    @Test
    void getHistory_fromEqualToTo_isAllowed() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(TaskFixtures.newTask(taskId)));

        assertThat(service.getHistory(taskId, T1, T1, 0, 20)).isSameAs(emptyPage);
    }

    @Test
    void getHistory_invalidPageOrSize_throwsInvalidRequest() {
        assertThatThrownBy(() -> service.getHistory(taskId, null, null, -1, 20))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.getHistory(taskId, null, null, 0, 0))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.getHistory(taskId, null, null, 0, 101))
                .isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(historyRepository);
    }

    @Test
    void getHistory_maxPageSize_isAccepted() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(TaskFixtures.newTask(taskId)));

        service.getHistory(taskId, null, null, 0, 100);

        verify(historyRepository).findByTaskId(taskId, null, null, 0, 100);
    }
}
