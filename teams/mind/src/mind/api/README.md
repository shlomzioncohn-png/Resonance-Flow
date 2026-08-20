# api

Outward service boundary — the only part of MIND other teams talk to.

- Inbox: publishes enriched task events; conforms to their canonical schema.
- Chain: publishes the per-task score (the exact API boundary); consumes their traversal API in week 7.
- Lens: publishes classification signals for manager-facing views.
- Relay: this image deploys on their platform; publish/consume on their event bus.

`main.py` is the FastAPI app and currently exposes `GET /health` for
Relay's liveness monitoring. Every route here uses the standard error
format from `contracts/errors.py`. Full contract (endpoints, error
format, versioning plan) is documented in `docs/API.md` — that's the
document to share with other teams, not this README.
