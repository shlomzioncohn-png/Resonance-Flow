package com.rnd.relay.event;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// Design doc §06 pitfall 1
@Component
class KafkaNotificationReplayService {

    private static final Logger log = LoggerFactory.getLogger(KafkaNotificationReplayService.class);

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);

    private final ConsumerFactory<String, TaskNotification> consumerFactory;
    private final String topic;
    private final int maxPolls;

    KafkaNotificationReplayService(
            @Qualifier("notificationReplayConsumerFactory") ConsumerFactory<String, TaskNotification> consumerFactory,
            @Value("${relay.kafka.task-notification-topic}") String topic,
            @Value("${relay.pipe.replay-buffer.max-polls:20}") int maxPolls) {
        this.consumerFactory = consumerFactory;
        this.topic = topic;
        this.maxPolls = maxPolls;
    }

    List<ActivityReplayBuffer.Entry> replaySince(String orgId, String userId, long lastOffset) {
        try (Consumer<String, TaskNotification> consumer = consumerFactory.createConsumer()) {
            List<PartitionInfo> partitions = consumer.partitionsFor(topic);
            if (partitions == null || partitions.isEmpty()) {
                return List.of();
            }

            TopicPartition topicPartition = new TopicPartition(topic, partitionFor(orgId, userId, partitions.size()));
            consumer.assign(List.of(topicPartition));

            long endOffset = consumer.endOffsets(List.of(topicPartition)).get(topicPartition);
            long seekOffset = lastOffset + 1;
            if (seekOffset >= endOffset) {
                return List.of();
            }
            consumer.seek(topicPartition, seekOffset);

            List<ActivityReplayBuffer.Entry> replay = new ArrayList<>();
            int polls = 0;
            while (consumer.position(topicPartition) < endOffset && polls++ < maxPolls) {
                ConsumerRecords<String, TaskNotification> records = consumer.poll(POLL_TIMEOUT);
                for (ConsumerRecord<String, TaskNotification> record : records) {
                    TaskNotification value = record.value();
                    if (value != null && orgId.equals(value.getOrgId()) && userId.equals(value.getUserId())
                            && value.getPushPolicy() == PushPolicy.ACTIVITY) {
                        replay.add(new ActivityReplayBuffer.Entry(
                                record.offset(), value, Instant.ofEpochMilli(record.timestamp())));
                    }
                }
            }
            return replay;
        } catch (Exception e) {
            log.warn("Kafka replay fallback failed for orgId={} userId={} lastOffset={} — resuming live only",
                    orgId, userId, lastOffset, e);
            return List.of();
        }
    }

    private static int partitionFor(String orgId, String userId, int numPartitions) {
        byte[] keyBytes = (orgId + ":" + userId).getBytes(StandardCharsets.UTF_8);
        return Utils.toPositive(Utils.murmur2(keyBytes)) % numPartitions;
    }
}
