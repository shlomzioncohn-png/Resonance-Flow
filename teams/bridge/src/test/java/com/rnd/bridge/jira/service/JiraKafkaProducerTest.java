package com.rnd.bridge.jira.service;

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

    @Mock
    private KafkaTemplate<String, Task> kafkaTemplate;

    @Spy
    private TaskValidator taskValidator;

    @InjectMocks
    private JiraKafkaProducer jiraKafkaProducer;

    @Test
    void publish_shouldSendTaskToJiraTopic() {
        Task task = createTask("jira:PROJ-1");

        SendResult<String, Task> sendResult = createSendResult(task);
        when(kafkaTemplate.send(anyString(), anyString(), any(Task.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        CompletableFuture<SendResult<String, Task>> result = jiraKafkaProducer.publish(task);

        assertThat(result).isCompletedWithValue(sendResult);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);

        verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), taskCaptor.capture());

        assertThat(topicCaptor.getValue()).isEqualTo(jiraKafkaProducer.TOPIC);
        assertThat(keyCaptor.getValue()).isEqualTo(task.sourceRef());
        assertThat(taskCaptor.getValue()).isEqualTo(task);
    }

    @Test
    void publish_shouldThrowExceptionWhenSourceRefIsNull() {
        Task task = new Task(null, null, "No sourceRef task", null, TaskStatus.NEW, null, null,
                Tier.TACTICAL, ExtractionProvenance.ETL, null, SourceType.JIRA_ISSUE, null, null, null, null);

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
                Tier.TACTICAL, ExtractionProvenance.ETL, null, SourceType.JIRA_ISSUE, sourceRef, null, null, null);
    }

    private SendResult<String, Task> createSendResult(Task task) {
        ProducerRecord<String, Task> record = new ProducerRecord<>(jiraKafkaProducer.TOPIC, task.sourceRef(), task);
        RecordMetadata metadata = new RecordMetadata(new TopicPartition(jiraKafkaProducer.TOPIC, 0), 0, 0, 0, 0, 0);
        return new SendResult<>(record, metadata);
    }
}
