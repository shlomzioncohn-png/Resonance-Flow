"""pending_creation_requests, pending_dependencies (#918)

Two tables, two unrelated kinds of state that both only live in memory
today and are lost on every restart:

- `pending_creation_requests` persists `task_creation.pending.PendingRequest`,
  keyed by `correlation_id` (the derived per-task id
  `send_creation_request` computes, not Bridge's own
  `bridge_correlation_id`, which is stored as a plain column). Every row
  still present is a request Inbox has not yet turned into a task.
  `reported_at` is nullable on purpose, not a boolean: `mark_reported`
  needs a single atomic statement (`UPDATE ... WHERE reported_at IS
  NULL`) to check-and-mark in one step -- see task_creation/README.md,
  "Three threads, one store".

- `pending_dependencies` persists a task's dependency mentions that did
  not resolve to a target yet (#834/#355's "no retry" scope, extended by
  #918's review to retry from the index consumer's `on_indexed` hook).
  `(task_id, mention)` is the primary key: the same task can have several
  mentions pending independently, and re-adding one already pending (a
  retry that fails again) is a no-op, not a duplicate row. Looked up by
  `tenant_id` (#841) when a newly indexed task might be the target one of
  them was waiting for.

Revision ID: 0005
Revises: 0004
Create Date: 2026-10-26

"""

from alembic import op

revision: str = "0005"
down_revision: str | None = "0004"
branch_labels: str | None = None
depends_on: str | None = None

REQUESTS_TABLE = "pending_creation_requests"
DEPENDENCIES_TABLE = "pending_dependencies"
DEPENDENCIES_TENANT_INDEX = "pending_dependencies_tenant_id_idx"


def upgrade() -> None:
    op.execute(
        f"""
        CREATE TABLE {REQUESTS_TABLE} (
            correlation_id UUID PRIMARY KEY,
            bridge_correlation_id UUID NOT NULL,
            revision INTEGER NOT NULL,
            task_index INTEGER NOT NULL,
            tenant_id UUID NOT NULL,
            dependency_mentions TEXT[] NOT NULL DEFAULT '{{}}',
            sent_at TIMESTAMPTZ NOT NULL,
            reported_at TIMESTAMPTZ
        );
        """
    )
    op.execute(
        f"""
        CREATE TABLE {DEPENDENCIES_TABLE} (
            task_id UUID NOT NULL,
            tenant_id UUID NOT NULL,
            mention TEXT NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            PRIMARY KEY (task_id, mention)
        );
        """
    )
    op.execute(
        f"CREATE INDEX {DEPENDENCIES_TENANT_INDEX} ON {DEPENDENCIES_TABLE} (tenant_id);"
    )


def downgrade() -> None:
    op.execute(f"DROP INDEX {DEPENDENCIES_TENANT_INDEX};")
    op.execute(f"DROP TABLE {DEPENDENCIES_TABLE};")
    op.execute(f"DROP TABLE {REQUESTS_TABLE};")
