package com.rnd.chain.service;

import com.rnd.chain.dto.FlowHealthFlag;
import com.rnd.chain.dto.FlowHealthPattern;
import com.rnd.chain.dto.FlowHealthSeverity;
import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.repository.FlowHealthFlagRepository;
import com.rnd.chain.repository.TransactionRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/** Read-only access to the flow-health flags; validates paging and delegates the filtered query. */
@Service
@RequiredArgsConstructor
public class FlowHealthFlagService {

    static final int MAX_PAGE_SIZE = 100;

    private final FlowHealthFlagRepository flagRepository;
    private final TransactionRunner transactionRunner;

    /**
     * @param tenantId the caller's tenant; flags of other tenants are never returned
     * @param pattern  null means any pattern
     * @param severity null means any severity
     * @return one page of flags ordered by rank
     * @throws InvalidRequestException if page is negative or size is not in 1..100
     */
    public PageResponse<FlowHealthFlag> getFlags(UUID tenantId, FlowHealthPattern pattern,
                                                 FlowHealthSeverity severity, int page, int size) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("page must be >= 0 and size must be in 1..100");
        }
        return transactionRunner.runInReadOnlyTransaction(
                () -> flagRepository.findPage(tenantId, pattern, severity, page, size));
    }
}
