# api/v1

MIND's versioned business endpoints — search, score, and whatever else
Phase 2 adds — everything under the `/v1/` prefix. See `docs/API.md`
("API versioning plan") for the rules: what counts as a breaking change,
when a route moves to `/v2` instead of changing in place, deprecation
policy.

`router` in `router.py` is empty today; there is nothing to version yet.
`GET /health` (in `api/main.py`) deliberately stays outside this
package — infrastructure endpoints aren't versioned.
