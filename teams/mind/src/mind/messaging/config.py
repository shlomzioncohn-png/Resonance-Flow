"""Kafka + schema-registry connection settings, read from the files Relay's
Helm chart mounts into the pod - not env vars.

    config.yaml   non-secret   -> $CONFIG_FILE_PATH  (/etc/resonance-flow/config/)
    secrets.yaml  secret       -> $SECRETS_FILE_PATH (/etc/resonance-flow/secret/)

Only the messaging-relevant keys are read here:

    kafka:
      bootstrap_servers: ...
      schema_registry_url: ...            # add this to components.mind.config
      producer: { acks: all, retries: 3 }
    # secrets.yaml, if the cluster uses SASL:
    kafka:
      sasl: { mechanism: ..., username: ..., password: ... }
"""

from __future__ import annotations

from dataclasses import dataclass, field

from mind import config_file


@dataclass
class SaslSettings:
    mechanism: str = ""
    username: str = ""
    password: str = ""

    @property
    def enabled(self) -> bool:
        return bool(self.mechanism)


@dataclass
class MessagingSettings:
    bootstrap_servers: str = ""
    schema_registry_url: str = ""
    acks: str = "all"
    retries: int = 3
    sasl: SaslSettings = field(default_factory=SaslSettings)

    def producer_conf(self) -> dict:
        conf = {
            "bootstrap.servers": self.bootstrap_servers,
            "acks": self.acks,
            "retries": self.retries,
            "enable.idempotence": True,
        }
        conf.update(self._security_conf())
        return conf

    def consumer_conf(self, group_id: str) -> dict:
        conf = {
            "bootstrap.servers": self.bootstrap_servers,
            "group.id": group_id,
            "auto.offset.reset": "earliest",
            "enable.auto.commit": False,
        }
        conf.update(self._security_conf())
        return conf

    def _security_conf(self) -> dict:
        if not self.sasl.enabled:
            return {}
        return {
            "security.protocol": "SASL_PLAINTEXT",
            "sasl.mechanism": self.sasl.mechanism,
            "sasl.username": self.sasl.username,
            "sasl.password": self.sasl.password,
        }


def load_settings() -> MessagingSettings:
    kafka = config_file.section(config_file.load_merged(), "kafka")
    producer = config_file.section(kafka, "producer")
    sasl = config_file.section(kafka, "sasl")
    return MessagingSettings(
        bootstrap_servers=kafka.get("bootstrap_servers", ""),
        schema_registry_url=kafka.get("schema_registry_url", ""),
        acks=producer.get("acks", "all"),
        retries=int(producer.get("retries", 3)),
        sasl=SaslSettings(
            mechanism=sasl.get("mechanism", ""),
            username=sasl.get("username", ""),
            password=sasl.get("password", ""),
        ),
    )
