"""The one reader of the two files Relay's Helm chart mounts into Mind's pod.

    config.yaml   non-secret -> $CONFIG_FILE_PATH  (components.mind.config)
    secrets.yaml  secret     -> $SECRETS_FILE_PATH (components.mind.secret)

The chart's key names are listed in teams/relay/helm/resonance-flow/README.md
("MIND (`components.mind`)"). Every module that needs a connection setting
reads it through here -- `mind.config` (vector store), `mind.messaging.config`
(Kafka) and `mind.extraction.providers.settings` (LLM) -- instead of opening
the files itself, so how a missing or broken file is handled is decided once.

config.yaml is required. If `CONFIG_FILE_PATH` is unset, or the file is
missing, unreadable, not valid YAML or not a mapping, `load_config()` raises
`ConfigFileError` -- there is no fallback path and no localhost default: a
service that starts against the wrong database is worse than one that
refuses to start (#481).

secrets.yaml is optional. The chart mounts its Secret `optional: true`, so a
pod can start before the Secret exists and find the directory empty. An unset
`SECRETS_FILE_PATH` or a missing file therefore reads as no secrets; a setting
that needs one (the vector-store password) is what fails, naming the key. A
file that *is* there but broken still raises.
"""

import os
from pathlib import Path
from typing import Any

import yaml

CONFIG_FILE_PATH_ENV = "CONFIG_FILE_PATH"
SECRETS_FILE_PATH_ENV = "SECRETS_FILE_PATH"

# Only used by the lenient readers below; the strict ones have no default path.
_CHART_CONFIG_FILE = "/etc/resonance-flow/config/config.yaml"
_CHART_SECRETS_FILE = "/etc/resonance-flow/secret/secrets.yaml"


class ConfigFileError(RuntimeError):
    """Mind's mounted configuration is missing or broken; startup must stop."""


def _parse(path: Path, env_var: str) -> dict[str, Any]:
    """The file's top-level mapping. Raises ConfigFileError on anything else."""

    where = f"{path} (from ${env_var})"
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as exc:
        raise ConfigFileError(f"cannot read {where}: {exc}") from exc
    try:
        loaded = yaml.safe_load(text)
    except yaml.YAMLError as exc:
        raise ConfigFileError(f"{where} is not valid YAML: {exc}") from exc
    if loaded is None:
        raise ConfigFileError(f"{where} is empty")
    if not isinstance(loaded, dict):
        raise ConfigFileError(
            f"{where} must be a YAML mapping of sections (vector_store:, kafka:, ...), "
            f"not a {type(loaded).__name__}"
        )
    return loaded


def config_file_path() -> Path:
    """Where config.yaml is, from `CONFIG_FILE_PATH`. Raises if it is unset or blank."""

    raw = os.environ.get(CONFIG_FILE_PATH_ENV, "").strip()
    if not raw:
        raise ConfigFileError(
            f"${CONFIG_FILE_PATH_ENV} is not set. Mind reads every connection setting "
            "from the file it names: in the cluster Relay's Helm chart sets it; "
            "locally see src/mind/api/README.md."
        )
    return Path(raw)


def load_config() -> dict[str, Any]:
    """config.yaml alone -- the non-secret settings. Required; see the module docstring."""

    path = config_file_path()
    if not path.is_file():
        state = "is a directory, not a file" if path.is_dir() else "does not exist"
        raise ConfigFileError(f"{path} (from ${CONFIG_FILE_PATH_ENV}) {state}")
    return _parse(path, CONFIG_FILE_PATH_ENV)


def load_secrets() -> dict[str, Any]:
    """secrets.yaml alone -- the credentials. Optional; see the module docstring."""

    raw = os.environ.get(SECRETS_FILE_PATH_ENV, "").strip()
    if not raw:
        return {}
    path = Path(raw)
    if not path.exists():
        return {}
    if not path.is_file():
        raise ConfigFileError(f"{path} (from ${SECRETS_FILE_PATH_ENV}) is a directory, not a file")
    # An empty secrets file is a Secret created with nothing in it yet.
    if not path.read_bytes().strip():
        return {}
    return _parse(path, SECRETS_FILE_PATH_ENV)


def load_merged() -> dict[str, Any]:
    """Both files as one tree, secrets.yaml winning on a clash.

    The chart splits one logical section across the two files -- e.g.
    `kafka.bootstrap_servers` in config.yaml, `kafka.sasl` in secrets.yaml --
    so callers want them merged back together.
    """

    return _deep_merge(load_config(), load_secrets())


def load_config_lenient() -> dict[str, Any]:
    """config.yaml the pre-#481 way: the chart's mount path when the variable is
    unset, {} for a missing file or a non-mapping.

    Kept only for `mind.config.ObjectStorageSettings` (#349), which landed with
    local defaults for a run without the mounted files. Whether it moves to the
    strict `load_config()` is an open #481 decision; until then its behaviour
    is unchanged.
    """

    return _read_yaml_lenient(os.environ.get(CONFIG_FILE_PATH_ENV, _CHART_CONFIG_FILE))


def load_merged_lenient() -> dict[str, Any]:
    """The pre-#481 reading, kept for the LLM settings alone.

    Falls back to the chart's mount paths when the variables are unset, and
    reads a missing file or a non-mapping as {}. The LLM part of the config
    contract is not agreed with Relay yet (#481), so its reader keeps exactly
    the behaviour it had; once the keys are confirmed it moves to
    `load_merged()` and this goes away.
    """

    return _deep_merge(
        _read_yaml_lenient(os.environ.get(CONFIG_FILE_PATH_ENV, _CHART_CONFIG_FILE)),
        _read_yaml_lenient(os.environ.get(SECRETS_FILE_PATH_ENV, _CHART_SECRETS_FILE)),
    )


def _read_yaml_lenient(path: str) -> dict[str, Any]:
    p = Path(path)
    if not p.is_file():
        return {}
    loaded = yaml.safe_load(p.read_text(encoding="utf-8"))
    return loaded if isinstance(loaded, dict) else {}


def _deep_merge(base: dict[str, Any], over: dict[str, Any]) -> dict[str, Any]:
    out = dict(base)
    for key, value in over.items():
        if isinstance(value, dict) and isinstance(out.get(key), dict):
            out[key] = _deep_merge(out[key], value)
        else:
            out[key] = value
    return out


def section(tree: dict[str, Any], key: str) -> dict[str, Any]:
    """`tree[key]` when it is a mapping, else {} -- `kafka:` with nothing under it
    reads as None, and a scalar where a section belongs is no section at all."""

    value = tree.get(key)
    return value if isinstance(value, dict) else {}
