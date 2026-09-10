package com.rnd.bridge.publish;

import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.task.model.ExtractionProvenance;
import com.rnd.bridge.task.model.RoleContext;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.model.TaskStatus;
import com.rnd.bridge.task.model.Tier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class LoggingPayloadPublisherTest {

    @Test
    void isAValidPayloadPublisherForTaskAndPublishingDoesNotThrow() {
        PayloadPublisher<Task> publisher = new LoggingPayloadPublisher<>();

        Task task = new Task(
                null, null,
                "Title",
                "Description",
                TaskStatus.NEW,
                3,
                null,
                Tier.TACTICAL,
                ExtractionProvenance.ETL,
                RoleContext.SALESPERSON,
                SourceType.CRM_DEAL,
                "crm:D-1",
                null,
                null,
                null,
                null);

        assertDoesNotThrow(() -> publisher.publish(task));
    }
}
