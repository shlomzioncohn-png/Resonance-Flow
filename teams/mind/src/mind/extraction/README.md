# extraction

LLM-based extraction + classification.

- Infers tasks that were never logged anywhere (the ML path, as opposed to Bridge's deterministic ETL path).
- Produces output conforming to the canonical `Task` schema (owned by Inbox — see MIND-01-System-Overview.md §2 for the schema sketch).
- Classification: urgency/importance, duration estimation.
- Every inferred task carries `confidence` + `extraction_provenance`.
