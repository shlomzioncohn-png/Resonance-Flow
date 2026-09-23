package com.rnd.relay.notification_delivery;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.ConsumerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.when;

// design doc §06 pitfall 1 — the Kafka fallback used when a reconnect's gap is bigger than the
// local ring buffer retains.
@ExtendWith(MockitoExtension.class)
class KafkaNotificationReplayServiceTest {

    private static final String TOPIC = "resonance.task-notifications";
    private static final String ORG_ID = "org-1";
    private static final String USER_ID = "user-1";
    private static final TopicPartition PARTITION_0 = new TopicPartition(TOPIC, 0);

    @Mock
    ConsumerFactory<String, TaskNotification> consumerFactory;

    @Test
    void topicWithNoKnownPartitionsIsAMiss() {
        MockConsumer<String, TaskNotification> consumer = newAssignableConsumer();
        // partitionsFor() returns null when the topic was never registered — left unregistered on purpose.
        when(consumerFactory.createConsumer()).thenReturn(consumer);
        KafkaNotificationReplayService service = new KafkaNotificationReplayService(consumerFactory, TOPIC, 20);

        assertThat(service.replaySince(ORG_ID, USER_ID, 0L)).isEmpty();
    }

    @Test
    void clientAlreadyAtTheHeadOfThePartitionIsAMissWithNothingToReplay() {
        MockConsumer<String, TaskNotification> consumer = newAssignableConsumer();
        registerSinglePartition(consumer);
        consumer.updateEndOffsets(Map.of(PARTITION_0, 5L));
        when(consumerFactory.createConsumer()).thenReturn(consumer);
        KafkaNotificationReplayService service = new KafkaNotificationReplayService(consumerFactory, TOPIC, 20);

        // lastOffset 4 -> seek to 5, which already equals the end offset
        assertThat(service.replaySince(ORG_ID, USER_ID, 4L)).isEmpty();
    }

    @Test
    void onlyMatchingActivityRecordsForThatUserAreReplayed() {
        MockConsumer<String, TaskNotification> consumer = newAssignableConsumer();
        registerSinglePartition(consumer);
        consumer.updateEndOffsets(Map.of(PARTITION_0, 4L));
        // addRecord() requires the partition to already be assigned; replaySince() assigns (and
        // seeks) again once it runs, which is a harmless no-op re-assignment of the same partition.
        consumer.assign(List.of(PARTITION_0));
        consumer.addRecord(record(0L, notification(ORG_ID, USER_ID, "keep-1", PushPolicy.ACTIVITY)));
        consumer.addRecord(record(1L, notification(ORG_ID, "someone-else", "wrong-user", PushPolicy.ACTIVITY)));
        consumer.addRecord(record(2L, notification("other-org", USER_ID, "wrong-org", PushPolicy.ACTIVITY)));
        consumer.addRecord(record(3L, notification(ORG_ID, USER_ID, "rollup-only", PushPolicy.ROLLUP_ONLY)));
        when(consumerFactory.createConsumer()).thenReturn(consumer);
        KafkaNotificationReplayService service = new KafkaNotificationReplayService(consumerFactory, TOPIC, 20);

        List<ActivityReplayBuffer.Entry> replay = service.replaySince(ORG_ID, USER_ID, -1L);

        assertThat(replay).hasSize(1);
        assertThat(replay.get(0).offset()).isEqualTo(0L);
        assertThat(replay.get(0).event().getTaskId()).isEqualTo("keep-1");
    }

    @Test
    void anUnproductiveScanStopsAtThePollCapInsteadOfHangingForever() {
        MockConsumer<String, TaskNotification> consumer = newAssignableConsumer();
        registerSinglePartition(consumer);
        // a huge end offset with no records ever added: pollForward would otherwise spin forever
        consumer.updateEndOffsets(Map.of(PARTITION_0, 1_000_000L));
        when(consumerFactory.createConsumer()).thenReturn(consumer);
        KafkaNotificationReplayService service = new KafkaNotificationReplayService(consumerFactory, TOPIC, 3);

        List<ActivityReplayBuffer.Entry> replay = assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(10), () -> service.replaySince(ORG_ID, USER_ID, -1L));

        assertThat(replay).isEmpty();
    }

    @Test
    void aBrokenConsumerFailsSafeToNoReplayHistoryInsteadOfPropagating() {
        Consumer<String, TaskNotification> brokenConsumer = mockThatThrowsOnPartitionsFor();
        when(consumerFactory.createConsumer()).thenReturn(brokenConsumer);
        KafkaNotificationReplayService service = new KafkaNotificationReplayService(consumerFactory, TOPIC, 20);

        assertThat(service.replaySince(ORG_ID, USER_ID, 0L)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Consumer<String, TaskNotification> mockThatThrowsOnPartitionsFor() {
        Consumer<String, TaskNotification> consumer = org.mockito.Mockito.mock(Consumer.class);
        when(consumer.partitionsFor(TOPIC)).thenThrow(new RuntimeException("broker unreachable"));
        return consumer;
    }

    private MockConsumer<String, TaskNotification> newAssignableConsumer() {
        return new MockConsumer<>(OffsetResetStrategy.EARLIEST);
    }

    private void registerSinglePartition(MockConsumer<String, TaskNotification> consumer) {
        Node node = new Node(0, "localhost", 9092);
        consumer.updatePartitions(TOPIC, List.of(new PartitionInfo(TOPIC, 0, node, new Node[] {node}, new Node[] {node})));
    }

    private ConsumerRecord<String, TaskNotification> record(long offset, TaskNotification value) {
        return new ConsumerRecord<>(TOPIC, 0, offset, ORG_ID + ":" + USER_ID, value);
    }

    private static TaskNotification notification(String orgId, String userId, String taskId, PushPolicy pushPolicy) {
        return TaskNotification.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOrgId(orgId)
                .setUserId(userId)
                .setTaskId(taskId)
                .setPushPolicy(pushPolicy)
                .build();
    }
}
