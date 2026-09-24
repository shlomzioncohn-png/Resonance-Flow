package com.rnd.bridge.jira.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rnd.bridge.task.BridgeTaskEventDto;
import com.rnd.bridge.task.TaskValidator;
import com.rnd.bridge.task.model.ExtractionProvenance;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.model.TaskStatus;
import com.rnd.bridge.task.model.Tier;
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

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JiraKafkaProducerTest {

    // ObjectMapper configured with Jackson's default (camelCase) - BridgeTaskEventDto
    // uses @JsonNaming(SnakeCaseStrategy.class) for per-class snake_case serialization.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Mock
    private KafkaTemplate<String, BridgeTaskEventDto> kafkaTemplate;

    @Spy
    private TaskValidator taskValidator;

    @InjectMocks
    private JiraKafkaProducer jiraKafkaProducer;

    @Test
    void publish_shouldSendBridgeTaskEventDtoToStructuredTopic() {
        Task task = createTask("jira:PROJ-1");

        SendResult<String, BridgeTaskEventDto> sendResult = createSendResult(task);
        when(kafkaTemplate.send(anyString(), anyString(), any(BridgeTaskEventDto.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        jiraKafkaProducer.publish(task);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BridgeTaskEventDto> eventCaptor = ArgumentCaptor.forClass(BridgeTaskEventDto.class);

        verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), eventCaptor.capture());

        BridgeTaskEventDto event = eventCaptor.getValue();
        assertThat(topicCaptor.getValue()).isEqualTo(jiraKafkaProducer.TOPIC);
        assertThat(jiraKafkaProducer.TOPIC).isEqualTo("bridge.structured.tasks");
        assertThat(keyCaptor.getValue()).isEqualTo(task.dedupKey());
        assertThat(event).isNotNull();
        assertThat(event.sourceRef()).isEqualTo(task.sourceRef());
        assertThat(event.sourceType()).isEqualTo(task.sourceType().name());
        assertThat(event.status()).isEqualTo(task.status().name());
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

    private Task createTask(String sourceRef) {
        return new Task(null, null, "Test Task", "Test description", TaskStatus.NEW, null, null,
                Tier.TACTICAL, ExtractionProvenance.ETL, null, SourceType.JIRA_ISSUE, sourceRef, null, null, null, null);
    }

    private SendResult<String, BridgeTaskEventDto> createSendResult(Task task) {
        BridgeTaskEventDto event = BridgeTaskEventDto.from(task);
        ProducerRecord<String, BridgeTaskEventDto> record = new ProducerRecord<>(jiraKafkaProducer.TOPIC, task.dedupKey(), event);
        RecordMetadata metadata = new RecordMetadata(new TopicPartition(jiraKafkaProducer.TOPIC, 0), 0, 0, 0, 0, 0);
        return new SendResult<>(record, metadata);
    }
}
