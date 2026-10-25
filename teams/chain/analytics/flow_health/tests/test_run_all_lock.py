"""The frozen-config guard of run_all."""
from flow_health import run_all


def test_hash_ignores_line_endings(tmp_path):
    (tmp_path / "a.yaml").write_bytes(b"x: 1\r\ny: 2\r\n")
    (tmp_path / "b.yaml").write_bytes(b"x: 1\ny: 2\n")
    assert run_all.config_hash(tmp_path / "a.yaml") == run_all.config_hash(tmp_path / "b.yaml")


def test_the_committed_config_matches_the_lock():
    assert run_all.LOCK.read_text("utf-8").strip() == run_all.config_hash(run_all.DEFAULT_PATH)


def test_a_changed_config_is_refused(tmp_path, capsys):
    other = tmp_path / "other.yaml"
    other.write_text("chronic_blocker:\n  min_active_ratio: 99\n", encoding="utf-8")
    assert run_all.main(["--data", str(tmp_path), "--config", str(other)]) == 2
    assert "frozen" in capsys.readouterr().err
