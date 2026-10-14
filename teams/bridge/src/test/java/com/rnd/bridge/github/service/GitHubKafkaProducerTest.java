package com.rnd.bridge.github.service;

import com.rnd.bridge.task.model.ExtractionProvenance;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.model.TaskStatus;
import com.rnd.bridge.task.model.Tier;
import com.rnd.bridge.task.TaskValidator;
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
class GitHubKafkaProducerTest {

    @Mock
    private KafkaTemplate<String, byte[]> kafkaTemplate;

    @Spy
    private TaskValidator taskValidator;

    @InjectMocks
    private GitHubKafkaProducer gitHubKafkaProducer;

    @Test
    void publish_shouldSendTaskToBridgeGithubTasksTopic() {
        Task task = createTask("github:repo:issue:1");

        SendResult<String, byte[]> sendResult = createSendResult();
        when(kafkaTemplate.send(anyString(), anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        CompletableFuture<SendResult<String, byte[]>> result = gitHubKafkaProducer.publish(task);

        assertThat(result).isCompletedWithValue(sendResult);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> taskCaptor = ArgumentCaptor.forClass(byte[].class);

        verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), taskCaptor.capture());

        assertThat(topicCaptor.getValue()).isEqualTo(gitHubKafkaProducer.TOPIC);
        assertThat(keyCaptor.getValue()).isEqualTo(task.sourceRef());
        assertThat(taskCaptor.getValue()).isNotNull();
        assertThat(taskCaptor.getValue()).isNotEmpty();
    }

    @Test
    void publish_shouldThrowExceptionWhenSourceRefIsNull() {
        Task task = new Task(null, null, "No sourceRef task", null, TaskStatus.NEW, null, null,
                Tier.TACTICAL, ExtractionProvenance.ETL, null, SourceType.GITHUB_ISSUE, null, null, null, null, null);

        assertThatThrownBy(() -> gitHubKafkaProducer.publish(task))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Task and sourceRef must not be null");
    }

    @Test
    void publish_shouldThrowExceptionWhenTaskIsNull() {
        assertThatThrownBy(() -> gitHubKafkaProducer.publish(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Task and sourceRef must not be null");
    }

    private Task createTask(String sourceRef) {
        return new Task(null, null, "Test Task", "Test description", TaskStatus.NEW, null, null,
                Tier.TACTICAL, ExtractionProvenance.ETL, null, SourceType.GITHUB_ISSUE, sourceRef, null, null, null, null);
    }

    private SendResult<String, byte[]> createSendResult() {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(gitHubKafkaProducer.TOPIC, "test-key", new byte[]{1, 2, 3});
        RecordMetadata metadata = new RecordMetadata(new TopicPartition(gitHubKafkaProducer.TOPIC, 0), 0, 0, 0, 0, 0);
        return new SendResult<>(record, metadata);
    }
}
