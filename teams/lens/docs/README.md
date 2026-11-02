# LENS docs

Team-wide docs for LENS (Frontend, Gateway, BFF). Docs about a single service live next to it:
`frontend/docs/`, `gateway/*.md`, `bff/README.md`.

- [lens-architecture-design.md](lens-architecture-design.md) - the three components and how they talk to each other.
- [status-model-migration-guide.md](status-model-migration-guide.md) - the move to Inbox's 8-value task status model.
- [workspace-screen-integration-plan.md](workspace-screen-integration-plan.md)

## Auth

- [auth/auth-password-task.md](auth/auth-password-task.md) - the task and design: password login, JWT RS256 from the Gateway.
- [auth/auth-e2e-test-guide.md](auth/auth-e2e-test-guide.md) - testing the login flow end to end.
- [auth/postman-auth-testing-checklist.md](auth/postman-auth-testing-checklist.md) - the same flow, step by step in Postman.

## Employee workspace

- [employee-workspace/employee-ui-design.md](employee-workspace/employee-ui-design.md) - the employee experience.
- [employee-workspace/employee-workspace-api-contract.md](employee-workspace/employee-workspace-api-contract.md) - Gateway ↔ BFF contract for My Workspace.
- [employee-workspace/employee-workspace-workplan.md](employee-workspace/employee-workspace-workplan.md) - who built what, per layer.
