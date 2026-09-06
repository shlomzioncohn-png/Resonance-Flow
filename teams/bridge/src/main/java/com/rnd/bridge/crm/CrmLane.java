package com.rnd.bridge.crm;

import com.rnd.bridge.core.AbstractIntakeLane;
import com.rnd.bridge.core.PayloadMapper;
import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.core.PayloadValidator;
import com.rnd.bridge.core.SourceReader;
import com.rnd.bridge.crm.mapper.DealToTaskMapper;
import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.publish.LoggingPayloadPublisher;
import com.rnd.bridge.task.TaskValidator;
import com.rnd.bridge.task.model.Task;
import org.springframework.stereotype.Component;

/**
 * Wires the CRM deal feed into the generic intake pipeline. No logic of its
 * own - it only supplies the four stages {@link AbstractIntakeLane} needs.
 */
@Component
public class CrmLane extends AbstractIntakeLane<Deal, Task> {

    private final CrmSourceReader sourceReader;
    private final DealToTaskMapper payloadMapper;
    private final TaskValidator payloadValidator;
    private final LoggingPayloadPublisher<Task> payloadPublisher;

    public CrmLane(
            CrmSourceReader sourceReader,
            DealToTaskMapper payloadMapper,
            TaskValidator payloadValidator,
            LoggingPayloadPublisher<Task> payloadPublisher) {
        this.sourceReader = sourceReader;
        this.payloadMapper = payloadMapper;
        this.payloadValidator = payloadValidator;
        this.payloadPublisher = payloadPublisher;
    }

    @Override
    protected SourceReader<Deal> sourceReader() {
        return sourceReader;
    }

    @Override
    protected PayloadMapper<Deal, Task> payloadMapper() {
        return payloadMapper;
    }

    @Override
    protected PayloadValidator<Task> payloadValidator() {
        return payloadValidator;
    }

    @Override
    protected PayloadPublisher<Task> payloadPublisher() {
        return payloadPublisher;
    }
}
