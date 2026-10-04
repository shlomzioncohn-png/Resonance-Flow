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

import yaml

_DEFAULT_CONFIG = "/etc/resonance-flow/config/config.yaml"
_DEFAULT_SECRETS = "/etc/resonance-flow/secret/secrets.yaml"


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


def _read_yaml(path: str) -> dict:
    p = Path(path)
    return yaml.safe_load(p.read_text()) if p.is_file() else {}


def _deep_merge(base: dict, over: dict) -> dict:
    out = dict(base)
    for k, v in over.items():
        out[k] = _deep_merge(out[k], v) if isinstance(v, dict) and isinstance(out.get(k), dict) else v
    return out


def load_settings() -> MessagingSettings:
    merged = _deep_merge(
        _read_yaml(os.environ.get("CONFIG_FILE_PATH", _DEFAULT_CONFIG)),
        _read_yaml(os.environ.get("SECRETS_FILE_PATH", _DEFAULT_SECRETS)),
    )
    kafka = merged.get("kafka", {}) or {}
    producer = kafka.get("producer", {}) or {}
    sasl = kafka.get("sasl", {}) or {}
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
