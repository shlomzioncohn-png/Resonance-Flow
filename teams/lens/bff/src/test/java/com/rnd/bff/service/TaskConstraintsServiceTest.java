package com.rnd.bff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.rnd.bff.client.ChainClient;
import com.rnd.bff.dto.ConstraintDTO;
import com.rnd.bff.exception.ConstraintNotFoundException;
import com.rnd.bff.exception.ServiceUnavailableException;
import com.rnd.bff.exception.TaskNotFoundException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class TaskConstraintsServiceTest {

    private static final String USER = "mgr_001";
    private static final String TENANT = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String TASK = "7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c01";

    @Mock private ChainClient chainClient;
    @Mock private UserTenantResolver tenantResolver;

    private TaskConstraintsService service;

    @BeforeEach
    void setUp() {
        service = new TaskConstraintsService(chainClient, tenantResolver);
        when(tenantResolver.tenantOf(USER)).thenReturn(TENANT);
    }

    private static ConstraintDTO constraint(String id, String status) {
        return ConstraintDTO.builder().constraintId(id).type("APPROVAL_NEEDED").status(status).build();
    }

    @Test
    void getConstraintsAsksChainWithTheUsersTenant() {
        List<ConstraintDTO> constraints = List.of(constraint("c1", "PENDING"));
        when(chainClient.getConstraints(TASK, TENANT)).thenReturn(Optional.of(constraints));

        assertThat(service.getConstraints(USER, TASK)).isEqualTo(constraints);
    }

    @Test
    void getConstraintsNotFoundIsTaskNotFound() {
        when(chainClient.getConstraints(TASK, TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getConstraints(USER, TASK))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void getConstraintsWithChainUnreachableIsServiceUnavailable() {
        when(chainClient.getConstraints(TASK, TENANT)).thenThrow(new ResourceAccessException("connect failed"));

        assertThatThrownBy(() -> service.getConstraints(USER, TASK))
                .isInstanceOfSatisfying(ServiceUnavailableException.class,
                        ex -> assertThat(ex.getServiceName()).isEqualTo("chain"));
    }

    @Test
    void updateStatusReturnsTheConstraintAsChainSavedIt() {
        when(chainClient.updateConstraintStatus(TASK, "c1", "SATISFIED", TENANT))
                .thenReturn(Optional.of(constraint("c1", "SATISFIED")));

        assertThat(service.updateStatus(USER, TASK, "c1", "SATISFIED").getStatus()).isEqualTo("SATISFIED");
    }

    @Test
    void updateStatusNotFoundIsConstraintNotFound() {
        when(chainClient.updateConstraintStatus(TASK, "missing", "WAIVED", TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(USER, TASK, "missing", "WAIVED"))
                .isInstanceOfSatisfying(ConstraintNotFoundException.class, ex -> {
                    assertThat(ex.getTaskId()).isEqualTo(TASK);
                    assertThat(ex.getConstraintId()).isEqualTo("missing");
                });
    }

    @Test
    void updateStatusWithChainUnreachableIsServiceUnavailable() {
        when(chainClient.updateConstraintStatus(TASK, "c1", "PENDING", TENANT))
                .thenThrow(new ResourceAccessException("connect failed"));

        assertThatThrownBy(() -> service.updateStatus(USER, TASK, "c1", "PENDING"))
                .isInstanceOfSatisfying(ServiceUnavailableException.class,
                        ex -> assertThat(ex.getServiceName()).isEqualTo("chain"));
    }

    @Test
    void updateStatusWithChainServerErrorIsServiceUnavailable() {
        when(chainClient.updateConstraintStatus(TASK, "c1", "SATISFIED", TENANT))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> service.updateStatus(USER, TASK, "c1", "SATISFIED"))
                .isInstanceOf(ServiceUnavailableException.class);
    }
}
