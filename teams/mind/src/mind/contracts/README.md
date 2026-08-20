# contracts

Pydantic models for everything crossing the `api/` boundary.

- Local mirror of Inbox's canonical `Task` schema (read-only conformance — Inbox owns and changes it, not us).
- Event payloads published to Inbox / Chain / Lens / Relay.
- Internal request/response models between MIND's own submodules.
- `errors.py` — `ErrorResponse`, MIND's standard error shape, plus
  `register_error_handlers(app)` which wires it into FastAPI once in
  `api/main.py`. Every endpoint, current and future, returns errors in
  this shape; do not invent a per-endpoint error format. Field reference
  and status-code table: `docs/API.md`.
