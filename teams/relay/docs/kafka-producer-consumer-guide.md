# Kafka producer/consumer guide

How to publish and consume events through Kafka in this system: one shared config file for the
Kafka/Schema Registry connection (same for every service), and your own settings for the
handful of things that are actually yours (group-id, topic, schema).

Examples below are in Java (Spring Boot + Spring Kafka), copied from a working setup already
running in this repo (`teams/relay`).

## 1. One shared config file for the connection, your own settings for the rest

The Kafka/Schema Registry **connection itself** is the same for every service — same broker,
same registry. It's delivered to you by the shared Helm chart as mounted files, not something
you write yourself — read the path from an environment variable:

| Variable | Value | Meaning |
|---|---|---|
| `CONFIG_FILE_PATH` | `/etc/resonance-flow/config/config.yaml` | your component's mounted config — the Kafka connection keys are in here |
| `SECRETS_FILE_PATH` | `/etc/resonance-flow/secret/secrets.yaml` | mounted **only** for a component that declares a `secret` block (SASL credentials); a component with none (e.g. Inbox) never gets this file |

Keys you'll find inside `config.yaml` (don't set these yourself, just read them):

| Key | Local default | Meaning |
|---|---|---|
| `bootstrap-servers` | `kafka:29092` | Kafka broker address |
| `security.protocol` | `PLAINTEXT` | `SASL_SSL` in the cloud |
| `sasl.jaas.config` | *(empty)* | credentials, cloud only — from `secrets.yaml` |
| `schema.registry.url` | `http://schema-registry:8080/apis/ccompat/v6` | Schema Registry endpoint — note the `/apis/ccompat/v6` suffix, it's required (Apicurio doesn't expose the Confluent-compatible API at the root path) |

**Your own settings — not in the shared file, decided and set by you:**

| Setting | Meaning |
|---|---|
| consumer `group-id` | your own consumer group id |
| consumer `auto-offset-reset` | `latest`, unless you have a reason to start from `earliest` |
| your topic name | the topic your event publishes/consumes on — yours alone, per section 2 |

These are per-service by nature (your group-id and topic aren't shared with anyone else), so
they don't belong in the shared connection file — set them directly in your own service.

## 2. Multiple topics, multiple schemas — this is not "the schema"

There is no single shared schema. This system already has several, each with its own topic:

- `task.created` — Inbox
- `bridge.raw.landed` — Bridge (unstructured intake)
- `bridge.github.tasks` — Bridge (GitHub)

Every event type your service publishes gets **its own** Kafka topic and **its own** `.proto`
schema file — never reuse someone else's topic or schema, and never route two different event
shapes through the same topic.

**You don't write the `.proto` file yourself.** Bring the field names, their types, and the
topic name — the schema gets created from that, the same way it happened for this repo's
existing drafts (see `shared/proto/task-created-event.proto` for what the result looks
like, including a `// kafkaTopic: ...` comment stating which topic it belongs to). Once it
exists, you'll get it back to review before anyone builds on top of it.

Enum values are prefixed — `TASK_STATUS_IN_PROGRESS`, not `IN_PROGRESS` — and every enum's
first value is `*_UNSPECIFIED = 0`. (proto3 enum value names share the enclosing scope, so the
prefix keeps them from colliding across enums in one file.) The schema author handles this.

## 3. The shared config file, and your own `application.yml`

**The shared connection file** (you don't write this — you're only shown it here so you know
what's in it and can read it correctly):
```yaml
spring:
  kafka:
    bootstrap-servers: kafka:29092
    properties:
      security.protocol: PLAINTEXT
      sasl.jaas.config:
      schema.registry.url: http://schema-registry:8080/apis/ccompat/v6
```

**Your own `application.yml`** — Spring loads the mounted `config.yaml` automatically (the chart
sets `SPRING_CONFIG_ADDITIONAL_LOCATION`), so you don't import it yourself. You set your own
group-id/topic (per section 1) plus the serializer setup (fixed, same for every environment).
For local dev, put the connection keys from the block above straight into this file.
```yaml
spring:
  kafka:
    consumer:
      group-id: your-service
      auto-offset-reset: latest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
      properties:
        spring.deserializer.value.delegate.class: io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer
        specific.protobuf.value.type: com.your.package.YourEvent
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer
      properties:
        # schemas are registered centrally (schema-subjects.tsv + the schema-sync-hook),
        # never by a producer
        auto.register.schemas: false
        use.latest.version: true
        # imports (e.g. task-created-event.proto) work as-is: Relay registers each imported
        # file under its own file name, which is where the default reference strategy looks
        # - don't set reference.subject.name.strategy

your:
  kafka:
    topic: your.topic.name
```

This is copied directly from `teams/relay` — only the class/package names are placeholders
for your own event. If your service isn't Java/Spring, the same split still applies: read
`CONFIG_FILE_PATH` (and `SECRETS_FILE_PATH` if you have a secret) yourself for the connection
keys, and set your own group-id/topic separately in your own code.

## 4. Producer

```java
@Service
public class EventProducer {

    private final KafkaTemplate<String, YourEvent> kafkaTemplate;
    private final String topic;

    public EventProducer(
            KafkaTemplate<String, YourEvent> kafkaTemplate,
            @Value("${your.kafka.topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(YourEvent event) {
        kafkaTemplate.send(topic, event.getId(), event);
    }
}
```

`YourEvent` is the class the Protobuf Maven plugin generates from your `.proto` schema file at
build time — you don't hand-write it (see `pom.xml` in `teams/relay` for the plugin setup).
Note there's no `.toString()` on the key here: Protobuf has no native UUID type, so id fields
are declared as `string` directly and the generated getter already returns a `String`.

## 5. Consumer

```java
@Component
public class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    @KafkaListener(topics = "${your.kafka.topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(YourEvent event) {
        log.info("Received event id={}", event.getId());
        // handle event
    }
}
```

## 6. What to bring, and what you'll get back

**What you bring:** the field names and types for your event, and the topic name you want to
publish/consume on. That's it — not a `.proto` file.

**What you get back:** a reviewed `.proto` schema for that event, added to
`shared/proto/` (same place as the other teams' drafts).

**What whoever deploys your service needs from you:** just your group-id and topic name — the
shared connection file and its path are already handled for every service the same way, you
don't need to ask for that separately.
