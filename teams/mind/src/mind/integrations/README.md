# integrations

Read-only clients for other teams' services, for when MIND asks a question over
HTTP instead of receiving an event. Nothing here writes to another team's
service. Today: Inbox's Users/Teams lookup (#131).

## Inbox directory (`inbox_directory.py`)

Turns a name found in text ("Dana") into candidate Inbox users and teams, so
that #132 can propose an owner by `user_id`. Inbox has no search by name, so the
client reads the lists of one tenant and the name is matched here.

`InboxDirectory.find_candidates(tenant_id, name)` returns a list of
`DirectoryCandidate(assignee_type, id, score)`, best first: users (`USER`) and
teams (`TEAM`) of that tenant, active ones only. An empty list means no match
**or** that Inbox could not be asked (see "Failure"); the log says which.

> **Status:** reading, failure handling, caching and matching are built. Still
> open: a check against a real Inbox (the service user below is in an Inbox PR,
> not yet on `main`), and setting the variables in the Helm values.

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

### How a name is matched (`name_matching.py`)

Word against word, never the whole strings: a string comparison is swayed by how
long the name is, so "sales team" came out close to "Sales" although "team" is a
word the name does not have. The rules are ours, written in code, not learned.

1. A name is its words, lower-cased, split on spaces, `.` and `_`. So
   `Dana  Cohen`, `dana.cohen` and `dana_cohen` are the same name.
2. Every word of the mention needs a partner among the words of the name: the
   same word, or one with a typo (`difflib` on the two words, `WORD_MATCH_MIN`
   and up). One word with no partner and the name is ruled out (score 0).
3. The similarity of a mention is the average similarity of its words to their
   partners, so a typo lowers it.
4. If the mention covers every word of the name it is a full match and scores
   that similarity: **1.0** for the same words, in any order. If a word of the
   name is left over (`Dana` for `Dana Cohen`) it is a partial match, worth the
   similarity times `PARTIAL_SCORE`.
5. A user is compared by display name, first and last name together, and
   username (the best of them counts); a team by its name. A first name is never
   offered alone next to a last name, or it would be an exact match for every
   Dana.
6. Below `MIN_SCORE` a candidate is dropped. A name that passes rule 2 scores at
   least `WORD_MATCH_MIN` x `PARTIAL_SCORE` (about 0.5), so `MIN_SCORE` (0.4) is a
   floor under the rule, not the rule itself.

A first name alone is returned as a candidate on purpose: #132 has to see that
there are two Danas to send no owner, and it can only do so if both come back
with the same score.

| Mention | In Inbox | Result |
|---|---|---|
| `Dana Cohen`, `dana  cohen`, `Cohen Dana` | Dana Cohen | 1.0 |
| `dana.cohen` | username `dana.cohen` | 1.0 |
| `Dana` | Dana Cohen | 0.70 (partial); a second Dana scores the same |
| `Cohen` | Dana Cohen | 0.70 |
| `Dena Cohen` | Dana Cohen | 0.88 (typo, whole name covered) |
| `Dena` | Dana Cohen | 0.52 (typo, partial) |
| `Dan` | Dana Cohen | 0.60: **every Dana comes back** |
| `Dani` | Daniel Katz | 0.56: close in spelling, so it comes back |
| `Sale` | team Sales | 0.89 |
| `Dana from finance`, `sales team` | Dana Cohen, team Sales | none: `from`, `finance` and `team` match no word |
| `Dana` | Danny Levi | none |
| `Bob` | Robert Katz | none |

Known limits:

- **Nicknames** are matched only when they are spelled close to the name.
  `Dani` finds `Daniel`, `Bob` does not find `Robert`.
- **A short name matches long ones**: `Dan` finds every Dan, Dana and Danny.
  That is what the ties are for, but the list can be long: there is no cap on
  the number of candidates.
- **Other scripts**: `Dana` does not match the same name written in Hebrew.
- **Extra words in the mention rule the name out.** #132 should extract only the
  name, not a role or a department.
- `WORD_MATCH_MIN` (0.71) is the middle of the gap between the pairs that must
  not match (0.67, `dana`/`danny`) and the ones that must (0.75, `dena`/`dana`).
  It rests on a handful of pairs, not on a measured set; revisit it with real
  names. The tests that pin the cases are in `tests/test_name_matching.py`.

### The actor

What Inbox returns depends on the role of the user in `X-Actor-Id`:

| Actor's role | `/users` | `/teams` |
|---|---|---|
| `SYSTEM_ADMIN` | every tenant | every tenant |
| `TENANT_ADMIN` | their tenant | their tenant |
| `TEAM_LEAD` | users in teams they lead | their tenant |
| `EMPLOYEE` | themselves | their tenant |

Mind needs the first two. With a `TEAM_LEAD` or `EMPLOYEE` actor Inbox answers
normally but with a **partial list** of users, so a person can be "not found"
with no error anywhere. `/teams` does not return an error for a `TEAM_LEAD`
either: only `SYSTEM_ADMIN` sees every tenant's teams, everyone else gets all the
teams of their own tenant (confirmed by the Inbox team, 2026-10-22; the
"403 for `TEAM_LEAD`" in issue #131 is wrong). The "teams they lead" narrowing
exists on `/users` only.

**Agreed actor (Inbox team, 2026-10-22):** a service user made for Mind.

| | |
|---|---|
| id (`X-Actor-Id`) | `00000000-0000-0000-0000-000000000003` |
| display name | `Mind Service`; no username and no password, it never logs in |
| role | `SYSTEM_ADMIN`, in the tenant "System" (the seed has 100 tenants, so `TENANT_ADMIN` would not do) |
| status | `ACTIVE` |
| where it comes from | an Inbox migration (V17), in an Inbox PR to `inbox-team`, **not yet on `main`**; once merged it exists in every environment when Inbox starts |

Because it is a `SYSTEM_ADMIN`, Inbox returns the users and teams of **every**
tenant, and the client keeps those of the tenant asked for. The seed has about
1,400 users, which is about 7 pages. An optional `tenant_id` filter on both
endpoints has been asked of the Inbox team; until then there is none.

Mind only reads (`GET`). Inbox trusts `X-Actor-Id` as sent (its "Known gap" in
`teams/inbox/CLAUDE.md`) and there is no decision about a token yet, so this id
works as a password for every tenant and is guessable (it follows the built-in
admin's `...0002`). In the cluster it should come from a Secret, not from the
values file; the Relay team has been asked.

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
(300) and `MIN_SCORE` (0.4) are starting points, not measured values. `WORD_MATCH_MIN`
(0.71) and `PARTIAL_SCORE` (0.7) are constants in `name_matching.py`, not
settings. `MIN_SCORE` must stay below `PARTIAL_SCORE`, or a lone first name would
be dropped; a test checks it. In the cluster the variables must be set in the
Helm values of the `mind` component.
