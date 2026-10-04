# User API — Authorization Policy

Enforced in `UserAuthorization` (`teams/inbox/src/main/java/com/rnd/inbox/user`), by `role`:

| Role | Read (`GET`) | Create (`POST`) | Update / Status (`PATCH`) |
|---|---|---|---|
| `SYSTEM_ADMIN` | Everyone, any tenant | Any tenant | Everyone, any tenant |
| `TENANT_ADMIN` | Their own tenant only | Their own tenant only | Their own tenant only |
| `TEAM_LEAD` | Users on a team they lead | ❌ never | Users on a team they lead |
| `EMPLOYEE` | Themselves only | ❌ never | Themselves only |

- "Their own tenant" / "a team they lead" is checked at the database-query level (not fetch-all-then-filter).
- Violating the scope returns `403 FORBIDDEN`; an unknown/missing actor returns `401 UNAUTHENTICATED`.
