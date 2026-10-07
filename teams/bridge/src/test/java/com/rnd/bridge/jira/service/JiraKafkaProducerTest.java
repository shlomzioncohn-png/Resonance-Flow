package com.rnd.bridge.jira.service;

import com.rnd.bridge.core.error.ValidationException;
import com.rnd.bridge.publish.kafka.KafkaPayloadPublisher;
import com.rnd.bridge.task.TaskValidator;
import com.rnd.bridge.task.model.ExtractionProvenance;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.model.TaskStatus;
import com.rnd.bridge.task.model.Tier;
import com.rnd.inbox.event.StructuredTaskEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JiraKafkaProducerTest {

    private static final String VALID_TENANT_ID = "11111111-1111-1111-1111-111111111111";

    @Mock
    private KafkaTemplate<String, StructuredTaskEvent> kafkaTemplate;

    @Spy
    private TaskValidator taskValidator;

    @InjectMocks
    private JiraKafkaProducer jiraKafkaProducer;

    @Test
    void publish_shouldSendStructuredTaskEventToStructuredTopic() {
        Task task = createTask("jira:PROJ-1", VALID_TENANT_ID);

        SendResult<String, StructuredTaskEvent> sendResult = createSendResult(task);
        when(kafkaTemplate.send(anyString(), anyString(), any(StructuredTaskEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        jiraKafkaProducer.publish(task);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<StructuredTaskEvent> eventCaptor = ArgumentCaptor.forClass(StructuredTaskEvent.class);

        verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), eventCaptor.capture());

        StructuredTaskEvent event = eventCaptor.getValue();
        assertThat(topicCaptor.getValue()).isEqualTo(jiraKafkaProducer.TOPIC);
        assertThat(jiraKafkaProducer.TOPIC).isEqualTo("bridge.structured.tasks");
        assertThat(keyCaptor.getValue()).isEqualTo(task.dedupKey());
        assertThat(event).isNotNull();
        assertThat(event.getSourceRef()).isEqualTo(task.sourceRef());
        assertThat(event.getSourceType().name()).isEqualTo("SOURCE_TYPE_" + task.sourceType().name());
        assertThat(event.getStatus().name()).isEqualTo("TASK_STATUS_" + task.status().name());
    }

    @Test
    void publish_shouldThrowExceptionWhenSourceRefIsNull() {
        Task task = new Task(null, null, "No sourceRef task", null, TaskStatus.NEW, null, null,
                Tier.TACTICAL, ExtractionProvenance.ETL, null, SourceType.JIRA_ISSUE, null, null, null, null, null);

        assertThatThrownBy(() -> jiraKafkaProducer.publish(task))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Task and sourceRef must not be null");
    }

    @Test
    void publish_shouldThrowExceptionWhenTaskIsNull() {
        assertThatThrownBy(() -> jiraKafkaProducer.publish(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Task and sourceRef must not be null");
    }

    @Test
    void publish_realJiraTaskWithNullTenantNowFailsLoudlyInsteadOfSendingAnUnvalidatedEvent() {
        // JIRA has no tenant concept wired up yet (a known, separate gap for the JIRA owner - not
        // fixed here). Before this class shared StructuredTaskEventMapper with CRM, a null-tenant
        // task still built and sent a BridgeTaskEventDto with tenantId: null - Kafka accepted it,
        // and only Inbox rejected it later, invisibly. The mapper validates tenant unconditionally
        // (same mapper CRM uses), so this now fails synchronously, inside Bridge, before any Kafka
        // call is attempted.
        Task task = createTask("jira:PROJ-2", null);

        assertThatThrownBy(() -> jiraKafkaProducer.publish(task))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("organizationId");

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void publishAll_oneNullTenantTaskAbortsEveryTaskAfterItInTheBatch() {
        // KafkaPayloadPublisher.publish() -> StructuredTaskEventMapper.from() throws before this
        // class's publish() ever calls kafkaTemplate.send() - and publishAll() below is a plain
        // forEach with no per-task try/catch (unlike AbstractIntakeLane's per-record isolation,
        // which this lane was never built on). Until the JIRA owner adds tenant resolution or
        // per-task error handling, a single null-tenant task - which is every JIRA task today -
        // stops the whole batch, not just itself. Flagging this explicitly for review rather than
        // changing publishAll()'s error handling here, which would be redesigning the JIRA lane.
        Task first = createTask("jira:PROJ-3", null);
        Task second = createTask("jira:PROJ-4", VALID_TENANT_ID);

        assertThatThrownBy(() -> jiraKafkaProducer.publishAll(List.of(first, second)))
                .isInstanceOf(ValidationException.class);

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    private Task createTask(String sourceRef, String organizationId) {
        return new Task(organizationId, null, "Test Task", "Test description", TaskStatus.NEW, null, null,
                Tier.TACTICAL, ExtractionProvenance.ETL, null, SourceType.JIRA_ISSUE, sourceRef, null, null, null, null);
    }

    private SendResult<String, StructuredTaskEvent> createSendResult(Task task) {
        StructuredTaskEvent event = com.rnd.bridge.task.StructuredTaskEventMapper.from(task);
        ProducerRecord<String, StructuredTaskEvent> record = new ProducerRecord<>(jiraKafkaProducer.TOPIC, task.dedupKey(), event);
        RecordMetadata metadata = new RecordMetadata(new TopicPartition(jiraKafkaProducer.TOPIC, 0), 0, 0, 0, 0, 0);
        return new SendResult<>(record, metadata);
    }
}
