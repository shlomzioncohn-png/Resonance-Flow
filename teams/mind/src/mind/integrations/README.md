# integrations

Read-only clients for other teams' services, for when MIND asks a question over
HTTP instead of receiving an event. Nothing here writes to another team's
service. Today: Inbox's Users/Teams lookup (#131).

## Inbox directory (`inbox_directory.py`)

Turns a name found in text ("Dana") into candidate Inbox users and teams, so
that #132 can propose an owner by `user_id`. Inbox has no search by name, so the
client reads the lists of one tenant and the name is matched here.

> **Status:** reading, failure handling and caching are built. **Matching is
> not**: `InboxDirectory.find_candidates` returns no candidates until it lands.
> The agreed actor (below) is also still open.

### What it calls

| Call | Used for |
|---|---|
| `GET /api/v1/users?page=&size=&sort=id,asc` | every user the actor can see |
| `GET /api/v1/teams?page=&size=&sort=id,asc` | every team the actor can see |

Pages of 200, all of them (`total_pages`), in a fixed order so a row added
between two calls cannot make a page skip or repeat people. Every call sends
`X-Actor-Id`.

### What it reads

Inbox answers in snake_case. From a user: `id`, `display_name`, `first_name`,
`last_name`, `username`, `status`, `tenant.id`. From a team: `id`, `name`,
`status`, `tenant.id`. Other fields are ignored.

Kept: rows whose `tenant.id` is the tenant asked for **and** whose `status` is
`ACTIVE`. Inbox has no filter for either, so it is done here. If Inbox returns
rows and none survive, a warning with counts and statuses is logged (never
names): that is what a wrong status spelling, or an actor that cannot see the
tenant, looks like.

> **To verify against a real Inbox:** that `status` arrives as `"ACTIVE"`, and
> the field names above. They were read from Inbox's code, not from a response.

### The actor

What Inbox returns depends on the role of the user in `X-Actor-Id`:

| Actor's role | `/users` | `/teams` |
|---|---|---|
| `SYSTEM_ADMIN` | every tenant | every tenant |
| `TENANT_ADMIN` | their tenant | their tenant |
| `TEAM_LEAD` | users in teams they lead | their tenant |
| `EMPLOYEE` | themselves | their tenant |

Mind needs the first two. With a `TEAM_LEAD` or `EMPLOYEE` actor Inbox answers
normally but with a **partial list**, so a person can be "not found" with no
error anywhere.

**Agreed actor: not yet.** Asked of the Inbox team: a dedicated service user for
Mind (not the built-in `SYSTEM_ADMIN` of migration V15, which is for one-off
set-up), without a password, `ACTIVE`, role `TENANT_ADMIN` (one tenant) or
`SYSTEM_ADMIN` (several), created in every environment. Fill in the id's source
and the date here once they answer.

Inbox trusts `X-Actor-Id` as sent (its "Known gap" in `teams/inbox/CLAUDE.md`);
there is no token yet.

### Failure: always "no candidates", never an exception

| What happens | Logged | Cached |
|---|---|---|
| address or actor not set | WARNING, once at start-up | -- |
| timeout, connection failure | WARNING | no |
| 401 / 403 | ERROR (the actor is wrong on every call) | no |
| any other HTTP error | WARNING | no |
| body is not JSON, or not the page we read | WARNING (field names only) | no |
| more than 100 pages, or more than `MAX_FETCH_SECONDS` | WARNING | no |

The time budget is checked while a page is arriving, not only between pages: the
HTTP timeout is for each wait on its own, so a server that sends a little every
few seconds would otherwise never trip it.

An address that is not an http(s) URL (no `http://`, a port that is not a
number) is not a runtime failure: the settings do not load and Mind stops at
start-up, naming `base_url`. An empty value means "not set".

A failure on either list gives no candidates at all: half an answer could hide
the person the name belongs to. Only an answer is cached, so a recovered Inbox
is used on the next lookup.

### Cache

The two lists of a tenant are kept for `CACHE_TTL_SECONDS` (default 300, `0` =
off), per tenant. A person added, disabled or moved in Inbox is seen up to that
long late. There is no lock: extraction runs one item at a time, and two threads
asking for the same tenant at once would only fetch it twice.

### Config

`MIND_INBOX_DIRECTORY__*`, see `.env.example` and `config.InboxDirectorySettings`.
Defaults of `TIMEOUT_SECONDS` (5), `MAX_FETCH_SECONDS` (15), `CACHE_TTL_SECONDS`
(300) and `MIN_SCORE` (0.6) are starting points, not measured values. In the
cluster the variables must be set in the Helm values of the `mind` component.
