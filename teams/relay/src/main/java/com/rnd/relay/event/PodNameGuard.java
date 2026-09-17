package com.rnd.relay.event;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class PodNameGuard implements InitializingBean {

    private final KafkaProperties kafkaProperties;

    @Value("${POD_NAME:}")
    private String podName;

    @Value("${KUBERNETES_SERVICE_HOST:}")
    private String kubernetesServiceHost;

    @Value("${relay.pipe.consumer-group-id}")
    private String consumerGroupId;

    public PodNameGuard(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (!StringUtils.hasText(kubernetesServiceHost)) {
            return;
        }

        if (!StringUtils.hasText(podName)) {
            throw new IllegalStateException(
                    "Running inside Kubernetes but POD_NAME is not set - check the Deployment's "
                            + "downward-API fieldRef for metadata.name. Refusing to start: without it, "
                            + "relay.pipe.consumer-group-id would fall back to a random value that another "
                            + "instance could also land on, risking two pods sharing the same Kafka consumer group.");
        }

        try (Admin admin = Admin.create(kafkaProperties.buildAdminProperties(null))) {
            Map<String, ConsumerGroupDescription> described = admin
                    .describeConsumerGroups(List.of(consumerGroupId))
                    .all()
                    .get(10, TimeUnit.SECONDS);
            ConsumerGroupDescription description = described.get(consumerGroupId);
            if (description != null && !description.members().isEmpty()) {
                throw new IllegalStateException(
                        "Consumer group " + consumerGroupId + " already has " + description.members().size()
                                + " active member(s) - POD_NAME=" + podName + " is not unique among currently "
                                + "running instances. Refusing to start: joining would split one pod's traffic "
                                + "across two instances instead of each pod independently reading the full topic.");
            }
        }
    }
}
