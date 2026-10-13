package pipe;

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

// relay.pipe.consumer-group-id (application.yml) falls back to a random value when
// INSTANCE_NAME is absent, so a plain local run (mvn spring-boot:run, docker compose) never
// fails to start. INSTANCE_NAME itself is a generic contract the app defines - it has no idea
// what supplies it; in Kubernetes, the Helm chart happens to populate it from the pod's
// metadata.name via the downward API, but that's the chart's business, not this class's.
//
// What this class does care about is whether it's running inside a Kubernetes cluster at all,
// since that's the one case where a missing INSTANCE_NAME is worth failing loudly for rather
// than quietly running on the random fallback - a cluster is exactly where multiple instances
// of this service run side by side, so a shared/undifferentiated group id becomes a real
// collision risk. KUBERNETES_SERVICE_HOST is set by the kubelet in every pod regardless of
// this service's own config (the same signal Kubernetes client libraries use to detect
// in-cluster execution), so it distinguishes "really in a cluster" from local/CI runs without
// this class needing to know anything else about how Kubernetes names things.
//
// A present INSTANCE_NAME still isn't proof the resulting consumer-group id is actually free
// to use - nothing guarantees this instance isn't racing an old instance of itself (e.g. a slow
// non-graceful exit whose consumer group hasn't been swept yet - see pipe-design.md §06.7) or
// starting up while some other process already holds that exact id. So this also asks the
// broker directly: if a group by this id already has live members, someone else has it, and
// joining anyway would mean two members silently splitting one user's partition between two
// instances instead of each one owning the full topic as the design assumes.
@Component
public class InstanceNameGuard implements InitializingBean {

    private final KafkaProperties kafkaProperties;

    @Value("${INSTANCE_NAME:}")
    private String instanceName;

    @Value("${KUBERNETES_SERVICE_HOST:}")
    private String kubernetesServiceHost;

    @Value("${relay.pipe.consumer-group-id}")
    private String consumerGroupId;

    public InstanceNameGuard(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        boolean inCluster = StringUtils.hasText(kubernetesServiceHost);
        if (!inCluster) {
            return;
        }

        if (!StringUtils.hasText(instanceName)) {
            throw new IllegalStateException(
                    "Running inside a Kubernetes cluster but INSTANCE_NAME is not set - check how the "
                            + "Deployment populates it. Refusing to start: without it, "
                            + "relay.pipe.consumer-group-id would fall back to a random value that another "
                            + "instance could also land on, risking two instances sharing the same Kafka consumer group.");
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
                                + " active member(s) - INSTANCE_NAME=" + instanceName + " is not unique among "
                                + "currently running instances. Refusing to start: joining would split one "
                                + "instance's traffic across two instances instead of each one independently "
                                + "reading the full topic.");
            }
        }
    }
}
