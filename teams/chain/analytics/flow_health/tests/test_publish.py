import json

import pytest

from flow_health import publish as pub

TENANT = "aaaaaaaa-0000-0000-0000-000000000001"
OTHER = "aaaaaaaa-0000-0000-0000-000000000002"


def _flag(flag_id, pattern="CHRONIC_BLOCKER", severity="high", score=0.9, entity_ids=("t1",), entity_type="task"):
    return {"flag_id": flag_id, "pattern": pattern, "entity_type": entity_type, "entity_ids": list(entity_ids),
            "score": score, "severity": severity, "explanation": "why", "evidence": {"days": 3}}


def _payload(flags, tenant=TENANT, as_of="2026-10-28T02:00:00+00:00"):
    meta = {"as_of": as_of, "seed": 1}
    if tenant:
        meta["tenant_id"] = tenant
    return {"meta": meta, "flags": flags}


class FakeConn:
    """Records what a DB-API connection is asked to do, and can fail on the n-th statement."""

    def __init__(self, fail_on=None):
        self.statements, self.commits, self.rollbacks, self.fail_on = [], 0, 0, fail_on

    def cursor(self):
        return self

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def execute(self, sql, params=()):
        if self.fail_on is not None and len(self.statements) == self.fail_on:
            raise RuntimeError("database went away")
        self.statements.append((sql, params))

    def commit(self):
        self.commits += 1

    def rollback(self):
        self.rollbacks += 1


def test_the_tenant_comes_from_the_file_and_an_option_may_only_repeat_it():
    assert pub.resolve_tenant(_payload([])) == TENANT
    assert pub.resolve_tenant(_payload([]), TENANT.upper()) == TENANT
    assert pub.resolve_tenant(_payload([], tenant=None), TENANT) == TENANT


def test_a_run_without_a_tenant_or_with_two_different_ones_is_refused():
    with pytest.raises(ValueError, match="no tenant"):
        pub.resolve_tenant(_payload([], tenant=None))
    with pytest.raises(ValueError, match="does not match"):
        pub.resolve_tenant(_payload([]), OTHER)
    with pytest.raises(ValueError):
        pub.resolve_tenant(_payload([], tenant="not-a-uuid"))


def test_rows_are_ranked_by_severity_then_score_and_carry_the_tenant_and_run_date():
    payload = _payload([_flag("c", severity="low", score=0.99), _flag("a", score=0.5), _flag("b", score=0.8)])

    rows = pub.build_rows(payload, TENANT)

    assert [(r[1], r[7]) for r in rows] == [("b", 1), ("a", 2), ("c", 3)]
    assert {r[0] for r in rows} == {TENANT}
    assert {r[10] for r in rows} == {"2026-10-28T02:00:00+00:00"}


def test_entity_ids_and_evidence_are_sent_as_json_text_keeping_order():
    flag = _flag("chain1", pattern="SLOW_CHAIN", entity_type="chain", entity_ids=("b", "a", "c"))
    row = pub.build_rows(_payload([flag]), TENANT)[0]

    assert json.loads(row[4]) == ["b", "a", "c"]
    assert json.loads(row[9]) == {"days": 3}


def test_a_file_without_a_run_date_or_with_a_repeated_flag_id_is_refused():
    with pytest.raises(ValueError, match="as_of"):
        pub.build_rows({"meta": {"tenant_id": TENANT}, "flags": []}, TENANT)
    with pytest.raises(ValueError, match="not unique"):
        pub.build_rows(_payload([_flag("x"), _flag("x")]), TENANT)


def test_an_unknown_pattern_or_severity_is_refused_with_a_clear_message():
    with pytest.raises(ValueError, match="unknown pattern or severity: x"):
        pub.build_rows(_payload([_flag("x", severity="extreme")]), TENANT)
    with pytest.raises(ValueError, match="unknown pattern or severity"):
        pub.build_rows(_payload([_flag("y", pattern="GROWING_BOTTLENECK")]), TENANT)


def test_publishing_replaces_only_that_tenants_flags_in_one_transaction():
    conn = FakeConn()

    written = pub.publish(conn, _payload([_flag("a"), _flag("b", score=0.4)]), TENANT)

    assert written == 2
    delete_sql, delete_params = conn.statements[0]
    assert delete_sql == pub.DELETE_SQL and delete_params == (TENANT,)
    assert [s[0] for s in conn.statements[1:]] == [pub.INSERT_SQL, pub.INSERT_SQL]
    assert all(s[1][0] == TENANT for s in conn.statements[1:])
    assert conn.commits == 1 and conn.rollbacks == 0


def test_a_run_with_no_flags_clears_the_tenants_old_ones():
    conn = FakeConn()

    assert pub.publish(conn, _payload([]), TENANT) == 0

    assert [s[0] for s in conn.statements] == [pub.DELETE_SQL]
    assert conn.commits == 1


def test_a_failure_part_way_rolls_everything_back_and_commits_nothing():
    conn = FakeConn(fail_on=2)  # the delete and the first insert went through, the second insert fails

    with pytest.raises(RuntimeError):
        pub.publish(conn, _payload([_flag("a"), _flag("b", score=0.4), _flag("c", score=0.3)]), TENANT)

    assert conn.rollbacks == 1 and conn.commits == 0


def test_a_bad_file_is_refused_before_the_database_is_touched():
    conn = FakeConn()

    with pytest.raises(ValueError):
        pub.publish(conn, _payload([_flag("x"), _flag("x")]), TENANT)

    assert conn.statements == [] and conn.commits == 0


def test_the_command_line_checks_a_file_without_a_database(tmp_path, capsys):
    path = tmp_path / "flags.json"
    path.write_text(json.dumps(_payload([_flag("a"), _flag("b", score=0.2)])), encoding="utf-8")

    assert pub.main(["--flags", str(path), "--dry-run"]) == 0

    out = capsys.readouterr().out
    assert f"tenant {TENANT}" in out and "with 2 from" in out


def test_the_command_line_refuses_without_a_tenant_or_a_database(tmp_path, capsys, monkeypatch):
    monkeypatch.delenv(pub.DSN_ENV, raising=False)
    no_tenant = tmp_path / "no_tenant.json"
    no_tenant.write_text(json.dumps(_payload([_flag("a")], tenant=None)), encoding="utf-8")
    with_tenant = tmp_path / "with_tenant.json"
    with_tenant.write_text(json.dumps(_payload([_flag("a")])), encoding="utf-8")

    assert pub.main(["--flags", str(no_tenant)]) == 2
    assert "no tenant" in capsys.readouterr().err
    assert pub.main(["--flags", str(with_tenant)]) == 2
    assert "no database" in capsys.readouterr().err
