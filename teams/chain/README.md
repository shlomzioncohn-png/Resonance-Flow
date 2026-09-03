# Chain Module

The Chain module is responsible for managing relationships between tasks in Resonance Flow.

It handles:

- Task dependencies
- Task blockers
- Task hierarchy traversal
- Task constraints
- Task eligibility (`isReady`)
- Scheduling update events

---

## Run Locally

From the project root:

```bash
cd teams/chain
mvn spring-boot:run
