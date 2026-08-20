# contracts

Pydantic models for everything crossing the `api/` boundary.

- Local mirror of Inbox's canonical `Task` schema (read-only conformance — Inbox owns and changes it, not us).
- Event payloads published to Inbox / Chain / Lens / Relay.
- Internal request/response models between MIND's own submodules.
