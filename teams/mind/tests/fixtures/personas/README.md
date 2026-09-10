# CI smoke fixture

Two throwaway scenarios so `mind-ci.yml` can run the harness end-to-end
(`python -m mind.evaluation.run --personas tests/fixtures/personas`) and prove
`run.py` still produces a report. **Not** the real evaluation set — that lives in
`eval/personas/` (same_tier/ + cross_tier/) and arrives in its own PR. Do not add
scenarios here.
