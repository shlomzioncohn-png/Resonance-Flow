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

// Design doc §06 pitfall 1, MISS path: when ActivityReplayBuffer's local ring buffer doesn't cover
// a reconnecting client's gap (too old, or this pod never saw the user before), fall back to
// reading the durable history straight from Kafka rather than giving up and resuming live-only.
//
// The notification topic is keyed by org_id:user_id (design doc §02.2), so every record for one
// user is guaranteed to land on a single partition as long as the partition count stays stable.
// That partition is recomputed here the same way Kafka's own default partitioner would place it
// (murmur2 hash of the key, mod partition count) instead of needing it recorded anywhere — see
// org.apache.kafka.clients.producer.internals.DefaultPartitioner for the reference algorithm this
// mirrors.
@Component
class KafkaNotificationReplayService {

    private static final Logger log = LoggerFactory.getLogger(KafkaNotificationReplayService.class);

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);
    // Bounds how long a single reconnect can spend catching up before giving up and resuming live —
    // a reconnect must not hang the client indefinitely waiting on a Kafka read.
    private static final int MAX_POLLS = 20;

    private final ConsumerFactory<String, TaskNotification> consumerFactory;
    private final String topic;

    KafkaNotificationReplayService(
            @Qualifier("notificationReplayConsumerFactory") ConsumerFactory<String, TaskNotification> consumerFactory,
            @Value("${relay.kafka.task-notification-topic}") String topic) {
        this.consumerFactory = consumerFactory;
        this.topic = topic;
    }

    // Never throws — a broker hiccup here should fall through to "resume live only", not break
    // the reconnect entirely.
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
            while (consumer.position(topicPartition) < endOffset && polls++ < MAX_POLLS) {
                ConsumerRecords<String, TaskNotification> records = consumer.poll(POLL_TIMEOUT);
                for (ConsumerRecord<String, TaskNotification> record : records) {
                    TaskNotification value = record.value();
                    if (value != null && orgId.equals(value.getOrgId()) && userId.equals(value.getUserId())) {
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
