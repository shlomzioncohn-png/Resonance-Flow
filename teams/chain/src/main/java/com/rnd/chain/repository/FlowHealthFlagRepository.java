package com.rnd.chain.repository;

import com.rnd.chain.dto.FlowHealthFlag;
import com.rnd.chain.dto.FlowHealthPattern;
import com.rnd.chain.dto.FlowHealthSeverity;
import com.rnd.chain.dto.PageResponse;

/** Read side of the flow-health flags; the analytics job is the only writer. */
public interface FlowHealthFlagRepository {

    /**
     * @param pattern  null means any pattern
     * @param severity null means any severity
     * @return one page of flags ordered by rank, then flag id
     */
    PageResponse<FlowHealthFlag> findPage(FlowHealthPattern pattern, FlowHealthSeverity severity, int page, int size);
}
