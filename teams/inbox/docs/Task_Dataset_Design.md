# Realistic Test Dataset Design — 100,000 Tasks

**Purpose:** design a realistic, reproducible dataset (~100,000 `tasks` rows, plus the
`tenants`/`users` rows that own them) for query- and index-performance testing.

**Status:** Draft — organization/tenant sizing finalized (this section). User-level and
field-level (status, tier, source_type, deadline, `is_available`, ...) distributions are
defined in later sections as the plan progresses.

---

## 1. Methodology

A uniform split (100 tenants × 1,000 tasks each) would not exercise the system the way real
traffic does — most production multi-tenant systems follow a **power-law / Pareto shape**: a
small number of large tenants hold a disproportionate share of the data, with a long tail of
small and near-empty tenants.

We model that with **six size tiers**. Within a tier, task counts are spread **linearly** from a
minimum to a maximum across the tenants in that tier (tenant *i* of *n* gets
`round(min + (max − min) × i / (n − 1))` tasks) — a simple, auditable rule, not an opaque random
draw. Every generator run uses a **fixed random seed**, so re-running it reproduces the exact
same dataset.

**Exact total.** Six tiers sized independently will not sum to exactly 100,000 by construction —
rounding alone can drift the total by dozens of rows. Rather than force artificial tie-breaks
across every tier, the single **Mega** tenant (tier 1, see below) absorbs the remainder:
`mega_tasks = 100,000 − sum(all other tenants)`. This keeps five tiers' ranges exactly as
designed, guarantees the grand total is exactly 100,000, and doubles as the "one especially large
organization" edge case the task asked for.

## 2. Tenant size tiers

| Tier | # Tenants | Tasks / tenant (range) | Tasks (tier total) | Share | Users / tenant (range) | Notes |
|---|---:|---|---:|---:|---|---|
| **Mega** | 1 | — (remainder) | **17,085** | 17.1% | 180–220 | Edge case: one dominant enterprise tenant |
| **Enterprise** | 4 | 3,500 – 6,500 | 20,000 | 20.0% | 40–90 | Large paying customers |
| **Large** | 15 | 900 – 2,500 | 25,500 | 25.5% | 15–40 | |
| **Medium** | 30 | 300 – 1,200 | 22,500 | 22.5% | 5–15 | |
| **Small** | 35 | 100 – 700 | 14,000 | 14.0% | 2–8 | |
| **Micro / Trial** | 15 | 2 – 120 | 915 | 0.9% | 1–3 | Edge case: several barely-used / near-empty tenants |
| **Total** | **100** | | **100,000** | 100% | | |

Sanity checks this design satisfies:
- **Not uniform** — tenant sizes span from 2 tasks (smallest Micro tenant) to 17,085 (Mega).
- **Edge cases included** — one outsized Mega tenant (§ task requirement: "especially large
  organization"), and several Micro tenants that are nearly empty (a realistic trial/onboarding
  state, and a useful edge case for queries that must handle sparse tenants).
- **Reproducible** — deterministic formula + fixed seed ⇒ identical dataset on every run.

## 3. Users per tenant

User counts scale with tenant size but **sub-linearly** (bigger tenants have more tasks per user
on average, not just more users) — see the ranges in the table above. Exact counts per tenant will
follow the same linear-spread-within-tier rule as tasks, with its own seeded sequence.

## 4. Task-to-user distribution within a tenant

Not uniform either: a minority of users ("power users") hold a disproportionate share of a
tenant's tasks — the "user with an unusually large number of tasks" edge case — and a task can
carry more than one user via `assignments` (`owner`, `assignee`, `responsible`, `collaborator`
roles). Three independent, per-tenant parameters drive this, applied in sequence:

1. **Sharing rate** (range 5%–35%, varies per tenant) — the share of the tenant's tasks that get
   a second assignee, **exactly two** (not three or more — a deliberate simplification, not a
   cap we've validated is realistic). This sets the total number of `assignments` rows for the
   tenant: `assignment_rows = tasks + shared_task_count` (≥ `tasks`), one extra row per shared
   task.
2. **Team-assignment rate** (range 10%–40%, varies per tenant — see §5) — splits those
   `assignment_rows` into a team-targeted pool (`team_id`) and a user-targeted pool (`user_id`),
   per the `assignments` constraint that a row is one or the other, never both.
3. **Concentration** (range 0.5–2.5, varies per tenant) — a Pareto/power-law shape parameter
   that spreads **both** pools the same way: the user-targeted pool across the tenant's users,
   and the team-targeted pool across the tenant's teams — the same value for both, so a
   tenant's "shape" (even vs. top-heavy) is consistent whether the load lands on people or on
   teams. Low = closer to even; high = a handful hold most of the rows.

All three parameters are drawn **per tenant** from their own range (same linear-spread + seed
method as §2/§3) rather than fixed globally — so tenants vary in shape too: some collaborative
and even, some single-owner and top-heavy, and everything between.

## 5. Team hierarchy within a tenant

**Decided** (confirmed by the Inbox lead): team assignments are in scope. Each tenant gets its
own team structure — `tenants → teams → team_members` — generated alongside its users:

- **Unaffiliated users (edge case)** — a flat **5% of each tenant's users, global** (not
  per-tenant) have no team at all: no row in `team_members`. Nothing in the schema requires
  every user to belong to a team (`team_members` is a plain join table; only `teams.team_lead_id`
  is `NOT NULL`, not any FK on `users`), and it matches real usage — an exec, a freelancer, a
  not-yet-onboarded hire. This is a distinct concept from §4 step 2 (team- vs. user-targeted
  *task* assignments) — it's about team *membership*, not task assignment.
- **Team size** (range 2–12 members, varies per team within a tenant, same linear-spread + seed
  method) — not uniform: some small teams, some large.
- **Team count per tenant** — every **affiliated** user (the 95% not carved out above) belongs to
  exactly one team, so `team_count = round(affiliated_users / avg_team_size)` (minimum 1), where
  `avg_team_size` itself is drawn per tenant from **4–8**. A Micro tenant with 1–3 affiliated
  users gets a single small team (team of one is a valid edge case). Team sizes within a tenant
  are then spread across the 2–12 range and remainder-corrected against the tenant's actual
  affiliated-user count (largest team absorbs the remainder, same rule as §1).
- **`team_lead_id`** — one member of each team (required, non-null per schema).
- Team-targeted `assignments` rows (§4, step 2) are spread across a tenant's teams by the same
  concentration parameter as user-targeted rows (§4, step 3) — not evenly.

## 6. Field-level distributions

Unlike tenant/user sizing, these are **global percentages, not per-tenant** — field values reflect
product usage patterns (which source, how work flows through statuses), not a trait that
plausibly differs by which specific tenant a task belongs to. Edge cases are folded in as small
tails inside the same global distribution rather than as a separate per-tenant mechanism.

**`status`**

| Value | % | Note |
|---|---:|---|
| `done` | 45% | active system — completed work accumulates over time |
| `in_progress` | 15% | |
| `new` | 12% | |
| `approved` | 8% | |
| `in_review` | 8% | |
| `snoozed` | 5% | requires `snoozed_until` (schema constraint) |
| `rejected` | 4% | |
| `cancelled` | 3% | genuine minority — distinct from `done`, not conflated with it |

**`source_type`**: email 20% · slack 18% · github_issue 15% · manual 12% · meeting 10% ·
crm_deal 8% · github_pr 7% · doc 4% · calendar 3% · whatsapp 2% · scanned_doc 1%.

**`extraction_provenance`** (+ `confidence`): `ml_inferred` 45% (confidence required, 0.4–0.99,
skewed high) · `etl` 35% (confidence usually NULL) · `manual` 20% (confidence NULL).

**`tier`** (nullable — Mind hasn't classified yet): NULL 20% · `operational` 52% · `tactical` 20%
· `strategic` 8%.

**`role_context`** (nullable, same reasoning as `tier`): NULL 20% · developer 28% · manager 16% ·
field_engineer 12% · salesperson 12% · higher_manager 8% · ceo 4%.

**`deadline`**: 30% none (NULL) · 15% overdue · 20% due within the next 7 days · 20% due within
30 days · 15% further out. The "next 7 days" bucket is itself mixed granularity, not one blob —
it includes tasks due in a **few hours** (same-day urgency) as well as tasks due in a **few days**,
both plausible in a live system.

**`is_available`** — computed by Chain, materialized here; correlated with `status`:
- `status` ∈ {done, cancelled, rejected, snoozed} → always `false`.
- `status` ∈ {new, in_review, approved, in_progress} → 75% `true`, 25% `false` (blocked /
  awaiting approval — the "blocked task" edge case).

**`priority_score`** (Chain's, core to prioritization — weighted high): 85% has a value, 15%
`NULL` (task too new to have entered Chain's scoring cycle yet). **Deliberately independent of
every other field** — the real score depends on Chain's dependency graph *and* a Mind LLM
judgment call, neither reproducible here even approximately; inventing a correlation with a field
we do have (e.g. `deadline`) would fabricate a relationship that doesn't exist in reality and
mislead the very query-performance testing this dataset is for. Shape instead of a flat field:
the 1–10 range is skewed toward the middle by averaging two independent uniform draws (closer to
a bell curve than a flat one — the same reasoning that gave `is_available`'s 75/25 split a
plausible *shape* without claiming to know Chain's real rule), plus the boundary tail (exact 1 or
10) already called out as an edge case below.

**`urgency`**: 70% has a value (1–5), 30% `NULL`. Same reasoning and technique as
`priority_score` — independent, skewed toward the middle via the two-draw average.

**`effort_estimate_minutes`** / **`effort_estimate_confidence`**: 60% of tasks have an estimate,
drawn from **15–480 minutes** (a quarter hour to a full 8-hour workday). When
`effort_estimate_minutes` is **not entered** (the other 40%, NULL), `effort_estimate_confidence`
is set to the **lowest** end of its range (≈0.0–0.1) rather than left NULL — it signals that a
downstream algorithm will substitute a default estimate there, and that default deserves a
confidence score marking it as a guess, not a real one. When an estimate *is* entered, confidence
is drawn from a normal-ish mid-to-high range instead.

**Additional edge-case tails** (folded into the percentages above, not a separate mechanism):
- ~1% of overdue tasks are **extremely** overdue (6+ months), not just recently past due.
- ~2–3% of scored tasks sit at the exact boundary values `priority_score` = 1 or = 10.
- ~2% of tasks are "minimal" rows — most optional fields (`tier`, `role_context`, `deadline`,
  `priority_score`, `urgency`) NULL at once, e.g. a bare manual entry with no context.
- Within `ml_inferred`, a small tail (confidence 0.4–0.5) represents near-guess classifications.
