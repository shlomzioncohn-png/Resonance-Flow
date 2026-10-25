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

import os
from dataclasses import dataclass, field
from pathlib import Path

from mind import config_file
from mind.config_file import ConfigFileError

_DEFAULT_KAFKA_SASL_SECRETS_DIR = "/etc/resonance-flow/secrets/kafka-sasl"


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

    def kafka_configured(self) -> bool:
        """Whether Kafka is set up at all.

        False when neither `kafka.bootstrap_servers` nor `kafka.schema_registry_url`
        is set: "Kafka is off", which a local run without a broker relies on (#924).
        Only one of them set is a broken config, not "off", so that raises a
        ConfigFileError naming the missing key.
        """
        missing = [
            f"kafka.{name}"
            for name, value in (
                ("bootstrap_servers", self.bootstrap_servers),
                ("schema_registry_url", self.schema_registry_url),
            )
            if not (value or "").strip()
        ]
        if len(missing) == 1:
            raise ConfigFileError(
                f"{missing[0]} is missing from config.yaml (${config_file.CONFIG_FILE_PATH_ENV}) "
                "while the other kafka key is set: an incomplete Kafka config, not Kafka "
                "switched off"
            )
        return not missing

    def require_client_keys(self) -> None:
        """Raise ConfigFileError unless both keys a Kafka client needs are set.

        Called where a producer or consumer is built (#481 review), not in
        load_settings(): there, empty values mean "Kafka is off". A client built
        without them would only log a librdkafka warning and then poll nothing,
        or fail much later on an "Invalid url" naming no key.
        """
        if not self.kafka_configured():
            raise ConfigFileError(
                "kafka.bootstrap_servers and kafka.schema_registry_url are missing from "
                f"config.yaml (${config_file.CONFIG_FILE_PATH_ENV}): a Kafka client can't be "
                "built without them"
            )

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
    password_file = (
        Path(os.environ.get("KAFKA_SASL_SECRETS_DIR", _DEFAULT_KAFKA_SASL_SECRETS_DIR))
        / "client-passwords"
    )
    password = (
        password_file.read_text().strip() if password_file.is_file() else sasl.get("password", "")
    )
    return MessagingSettings(
        bootstrap_servers=kafka.get("bootstrap_servers", ""),
        schema_registry_url=kafka.get("schema_registry_url", ""),
        acks=producer.get("acks", "all"),
        retries=int(producer.get("retries", 3)),
        sasl=SaslSettings(
            mechanism=sasl.get("mechanism", ""),
            username=sasl.get("username", ""),
            password=password,
        ),
    )
