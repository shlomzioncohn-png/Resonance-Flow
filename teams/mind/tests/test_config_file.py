"""Tests for mind.config_file, the one reader of Relay's two mounted files.

config.yaml is required and every way of it being absent or broken must fail
loudly (#481); secrets.yaml is optional, because the chart mounts its Secret
`optional: true`.
"""

from pathlib import Path

import pytest

from mind import config_file
from mind.config_file import ConfigFileError


@pytest.fixture(autouse=True)
def _mounted(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """Point both paths at a temp directory, so no test reads a real mount.

    The files themselves are not created: each test writes what it needs.
    """

    monkeypatch.setenv("CONFIG_FILE_PATH", str(tmp_path / "config.yaml"))
    monkeypatch.setenv("SECRETS_FILE_PATH", str(tmp_path / "secrets.yaml"))
    return tmp_path


def _write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


# --- config.yaml: required -------------------------------------------------


def test_reads_the_file_config_file_path_names(_mounted: Path) -> None:
    _write(_mounted / "config.yaml", "kafka:\n  bootstrap_servers: broker:9092\n")

    assert config_file.load_config() == {"kafka": {"bootstrap_servers": "broker:9092"}}


@pytest.mark.parametrize("value", [None, "", "   "])
def test_unset_or_blank_config_file_path_fails(
    monkeypatch: pytest.MonkeyPatch, value: str | None
) -> None:
    if value is None:
        monkeypatch.delenv("CONFIG_FILE_PATH")
    else:
        monkeypatch.setenv("CONFIG_FILE_PATH", value)

    with pytest.raises(ConfigFileError, match=r"\$CONFIG_FILE_PATH is not set"):
        config_file.load_config()


def test_missing_config_file_fails_naming_the_path(_mounted: Path) -> None:
    with pytest.raises(
        ConfigFileError, match=r"config\.yaml \(from \$CONFIG_FILE_PATH\) does not exist"
    ):
        config_file.load_config()


def test_config_path_that_is_a_directory_fails(
    _mounted: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("CONFIG_FILE_PATH", str(_mounted))

    with pytest.raises(ConfigFileError, match="is a directory, not a file"):
        config_file.load_config()


def test_unreadable_config_file_fails(_mounted: Path) -> None:
    (_mounted / "config.yaml").write_bytes(b"\xff\xfe not utf-8 \x80")

    with pytest.raises(ConfigFileError, match="cannot read"):
        config_file.load_config()


def test_invalid_yaml_fails(_mounted: Path) -> None:
    _write(_mounted / "config.yaml", "kafka:\n  bootstrap_servers: [unclosed\n")

    with pytest.raises(ConfigFileError, match="is not valid YAML"):
        config_file.load_config()


@pytest.mark.parametrize("content", ["", "# only a comment\n"])
def test_empty_config_file_fails(_mounted: Path, content: str) -> None:
    _write(_mounted / "config.yaml", content)

    with pytest.raises(ConfigFileError, match="is empty"):
        config_file.load_config()


@pytest.mark.parametrize(
    ("content", "kind"), [("- a\n- list\n", "list"), ("just a string\n", "str")]
)
def test_config_file_that_is_not_a_mapping_fails(_mounted: Path, content: str, kind: str) -> None:
    _write(_mounted / "config.yaml", content)

    with pytest.raises(ConfigFileError, match=f"must be a YAML mapping.*not a {kind}"):
        config_file.load_config()


def test_merged_fails_without_config_even_when_secrets_exist(_mounted: Path) -> None:
    _write(_mounted / "secrets.yaml", "vector_store:\n  password: s3cret\n")

    with pytest.raises(ConfigFileError, match="does not exist"):
        config_file.load_merged()


# --- secrets.yaml: optional ------------------------------------------------


def test_reads_the_file_secrets_file_path_names(_mounted: Path) -> None:
    _write(_mounted / "secrets.yaml", "vector_store:\n  password: s3cret\n")

    assert config_file.load_secrets() == {"vector_store": {"password": "s3cret"}}


def test_unset_secrets_file_path_means_no_secrets(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("SECRETS_FILE_PATH")

    assert config_file.load_secrets() == {}


def test_missing_secrets_file_means_no_secrets(_mounted: Path) -> None:
    """The chart's Secret volume is optional: the pod can start before it exists."""

    assert config_file.load_secrets() == {}


@pytest.mark.parametrize("content", ["", "  \n"])
def test_empty_secrets_file_means_no_secrets(_mounted: Path, content: str) -> None:
    _write(_mounted / "secrets.yaml", content)

    assert config_file.load_secrets() == {}


def test_broken_secrets_file_still_fails(_mounted: Path) -> None:
    """Optional means "may be absent", not "may be garbage"."""

    _write(_mounted / "secrets.yaml", "vector_store: [unclosed\n")

    with pytest.raises(ConfigFileError, match=r"\$SECRETS_FILE_PATH.*is not valid YAML"):
        config_file.load_secrets()


def test_secrets_file_that_is_not_a_mapping_fails(_mounted: Path) -> None:
    _write(_mounted / "secrets.yaml", "- password\n")

    with pytest.raises(ConfigFileError, match="must be a YAML mapping"):
        config_file.load_secrets()


def test_secrets_path_that_is_a_directory_fails(
    _mounted: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("SECRETS_FILE_PATH", str(_mounted))

    with pytest.raises(ConfigFileError, match="is a directory, not a file"):
        config_file.load_secrets()


# --- merging ---------------------------------------------------------------


def test_merge_joins_a_section_split_across_the_two_files(_mounted: Path) -> None:
    """The chart puts kafka.bootstrap_servers in config.yaml and kafka.sasl in secrets.yaml."""

    _write(
        _mounted / "config.yaml",
        "vector_store:\n  url: db:5432\nkafka:\n  bootstrap_servers: broker:9092\n",
    )
    _write(_mounted / "secrets.yaml", "kafka:\n  sasl:\n    mechanism: PLAIN\n")

    assert config_file.load_merged() == {
        "vector_store": {"url": "db:5432"},
        "kafka": {"bootstrap_servers": "broker:9092", "sasl": {"mechanism": "PLAIN"}},
    }


def test_secrets_win_on_a_clash(_mounted: Path) -> None:
    _write(_mounted / "config.yaml", "kafka:\n  sasl:\n    password: from-config\n")
    _write(_mounted / "secrets.yaml", "kafka:\n  sasl:\n    password: from-secret\n")

    assert config_file.load_merged()["kafka"]["sasl"]["password"] == "from-secret"


def test_merge_does_not_modify_what_it_was_given() -> None:
    base = {"kafka": {"bootstrap_servers": "a"}}

    config_file._deep_merge(base, {"kafka": {"sasl": {"mechanism": "PLAIN"}}})

    assert base == {"kafka": {"bootstrap_servers": "a"}}


@pytest.mark.parametrize(
    ("tree", "expected"),
    [
        ({"kafka": {"bootstrap_servers": "b"}}, {"bootstrap_servers": "b"}),
        ({"kafka": None}, {}),  # `kafka:` with nothing under it
        ({"kafka": "broker:9092"}, {}),  # a scalar where a section belongs
        ({}, {}),
    ],
)
def test_section_is_always_a_mapping(tree: dict, expected: dict) -> None:
    assert config_file.section(tree, "kafka") == expected


# --- the lenient reader, kept for the LLM settings only ---------------------


def test_lenient_reader_tolerates_missing_files(_mounted: Path) -> None:
    assert config_file.load_merged_lenient() == {}


def test_lenient_reader_falls_back_to_the_chart_mount_paths(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    seen: list[str] = []
    monkeypatch.delenv("CONFIG_FILE_PATH")
    monkeypatch.delenv("SECRETS_FILE_PATH")
    monkeypatch.setattr(config_file, "_read_yaml_lenient", lambda path: seen.append(path) or {})

    config_file.load_merged_lenient()

    assert seen == [
        "/etc/resonance-flow/config/config.yaml",
        "/etc/resonance-flow/secret/secrets.yaml",
    ]
