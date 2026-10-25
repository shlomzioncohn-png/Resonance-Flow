"""#474: the one place that reads Chain's traversal API, over `/ancestors`
and `/children`. If Chain's API changes, this is the only file to edit.

Chain's own `ancestorIds` traversal silently truncates on a cycle or a
dangling parent -- a normal 200 with whatever chain it collected, no error,
no field marking it short. That makes a cycle invisible through `/ancestors`
specifically; it says nothing about `/children`, which has no documented
truncation at all -- `get_direct_children` can still walk a real cycle (the
rescore cascade's own problem to guard against, not this file's).

Every failure -- timeout, connection refused, a non-2xx, a body that is not
the shape expected -- is swallowed here and logged, never raised: #474
requires propagation to degrade to the baseline score when Chain is
unavailable, never to crash and never to silently misread a wrong answer as
a right one.
"""

from __future__ import annotations

import logging
from typing import Any, Self

import httpx
from pydantic_settings import BaseSettings, SettingsConfigDict

from mind.config import _ENV_FILE

logger = logging.getLogger("mind.scoring")

# Chain's in-cluster service, per teams/relay/helm/resonance-flow/values.yaml.
DEFAULT_BASE_URL = "http://resonance-flow-chain:80"
# In-cluster, so a healthy Chain answers in low single-digit ms; this exists
# to fail fast on a struggling one, not to accommodate a normal slow reply.
# Up to two calls (ancestors, children) can happen per task under
# ScorePublisher's lock -- round-9 review: at the old 2s this was up to 4s
# of every other task's scoring blocked on one slow Chain.
DEFAULT_TIMEOUT_SECONDS = 0.2


class ChainHierarchySettings(BaseSettings):
    """Chain's own base URL -- not a secret, so no mounted secrets.yaml.

    `timeout_seconds` is kept short on purpose: #474 asks a network per
    scored task, so a slow Chain must fail fast onto the baseline rather than
    stall scoring.
    """

    model_config = SettingsConfigDict(env_prefix="MIND_CHAIN__", env_file=_ENV_FILE, extra="ignore")

    base_url: str = DEFAULT_BASE_URL
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS


def load_chain_hierarchy_settings() -> ChainHierarchySettings:
    return ChainHierarchySettings()


class ChainHierarchyClient:
    """A synchronous client over the two Chain endpoints #474 needs.

    Synchronous to match `RankingModel.score`'s own signature: the model is
    called one task at a time from inside a Kafka consumer's handler thread,
    so concurrency belongs to whoever owns that loop, not here.
    """

    def __init__(
        self,
        settings: ChainHierarchySettings | None = None,
        client: httpx.Client | None = None,
    ) -> None:
        self._settings = settings if settings is not None else load_chain_hierarchy_settings()
        self._owns_client = client is None
        self._client = client or httpx.Client(
            base_url=self._settings.base_url,
            timeout=httpx.Timeout(self._settings.timeout_seconds),
        )

    def get_ancestors(self, task_id: str) -> list[str]:
        """`task_id`'s whole ancestor chain, nearest-parent-first -- empty if
        it has none or Chain could not be asked. #474's `ChainAncestorSource`
        needs every ancestor's own value, not just the direct parent's, since
        averaging does not compose recursively the way summing once did."""

        ancestors = self._get_list(f"/tasks/{task_id}/ancestors")
        ids = [self._task_id_of(node, task_id) for node in ancestors]
        return [ancestor_id for ancestor_id in ids if ancestor_id is not None]

    def get_direct_children(self, task_id: str) -> list[str]:
        """`task_id`'s immediate children, for the rescore cascade (#474) --
        empty if it has none or Chain could not be asked."""

        children = self._get_list(f"/tasks/{task_id}/children")
        ids = [self._task_id_of(child, task_id) for child in children]
        return [child_id for child_id in ids if child_id is not None]

    def _get_list(self, path: str) -> list[Any]:
        try:
            response = self._client.get(path)
        except httpx.TimeoutException:
            logger.warning("scoring: Chain did not answer %s within %ss", path, self._settings.timeout_seconds)
            return []
        except httpx.HTTPError as exc:
            logger.warning("scoring: could not reach Chain at %s: %s: %s", path, type(exc).__name__, exc)
            return []

        if response.status_code != httpx.codes.OK:
            logger.warning("scoring: Chain returned %s for %s", response.status_code, path)
            return []

        try:
            payload = response.json()
        except ValueError:
            logger.warning("scoring: Chain's response to %s is not JSON", path)
            return []

        if not isinstance(payload, list):
            logger.warning("scoring: Chain's response to %s is %s, not a list", path, type(payload).__name__)
            return []
        return payload

    @staticmethod
    def _task_id_of(node: Any, requested_for: str) -> str | None:
        if not isinstance(node, dict) or not isinstance(node.get("id"), str):
            logger.warning(
                "scoring: Chain returned a malformed node for %s, skipping it", requested_for
            )
            return None
        return node["id"]

    def close(self) -> None:
        """Close the HTTP client, if this instance owns it."""

        if self._owns_client:
            self._client.close()

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *exc_info: object) -> None:
        self.close()
