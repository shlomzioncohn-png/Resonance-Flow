# Manager / Projects page — work plan

**Update (confirmed with Rachel Zahler, mentor):** this view now lives at `/manager`
("Manager Dashboard" in the sidebar) instead of a separate `/manager/projects` route —
there's no separate "Projects" nav entry. Longer-term the plan is two distinct views:
"Dashboard" = charts/graphs (not yet built), "Projects" = a *table* of project data
(owner, description, deadline, etc.) as a possible follow-up if time allows — starting
small with the dashboard/graphs first. What's built below (KPI tiles + a project-card
grid) is serving as the Manager Dashboard's content for now.

Source: Figma file "Resonance-Flow — Manager Screens" (`fileKey=Imb8m7ETbskrUCoVQtpFLG`),
frame **"Manager / Projects"** (`nodeId=4:21`) — confirmed against a full-fidelity
screenshot the user provided directly (mock data: 12 projects, "Sort: Health" filter).

Note: a link to `nodeId=4:22` was also given — that node is just the `Sidebar / Manager`
component *instance* nested inside frame `4:21`, not a separate design. No action needed;
it already matches the existing `components/layout/Sidebar`.

**Flag with the mentor:** a Figma Make link was also given
(`figma.com/make/gjQzCvXXoa3geOjs1fJTuF`, "3D Rotating Business Card") — unrelated to a
manager projects dashboard, not used here. Confirm it wasn't pasted by mistake.

**Scope, confirmed with the user:** she owns only the **all-projects list page**. The
single-project detail page (`app/(app)/projects/[projectId]/page.tsx`, and Figma's own
"Manager / Project Dashboard" frame `4:80`, which is itself undesigned/empty) is out of
scope — link to it, don't build it out.

**Before implementing:** re-run `get_design_context` on `nodeId=4:21` (skill
`figma-design-to-code`) for exact colors/spacing — the Figma Starter-plan rate limit was
hit while writing this plan, so exact tokens weren't pulled; the structure below comes
from `get_metadata` plus the screenshot.

## Route & navigation

- New route: `app/(app)/manager/projects/page.tsx`.
- Add a nav item under "Manager" in `components/layout/Sidebar/navConfig.tsx`:
  `{ label: 'Projects', href: '/manager/projects' }`.
- Each `ProjectCard` links to `app/(app)/projects/[projectId]/page.tsx` (existing
  `EmptyState` placeholder — update its description, which currently says "Reached from
  Manager → Workload", to also mention Projects).

## Component reuse — check this before building anything new

The screenshot confirms most of this page is **already-built components**, not new UI:

- **Status pill** ("Healthy" / "At Risk" / "Critical" / "No data") is exactly
  `components/ui/StatusBadge` — its `StatusTone` type (`healthy` / `warning` / `critical`
  / `neutral`) maps 1:1. Reuse it directly; do not build a separate "StatusPill".
- **Avatar stack** ("●●● +2") is MUI's built-in `AvatarGroup` — no custom component needed.
- **Progress bar** is MUI's `LinearProgress` (`variant="determinate"`), colored per health
  tone via `theme.palette.status[...]` (same palette `StatusBadge` already reads).

**Actually new components needed:**

- `components/ui/KpiTile/` — big colored number + label. 4 instances in a row: "Total
  projects" (neutral/dark number), "Healthy" (green), "At risk" (orange), "Critical" (red).
- `components/ui/FilterChip/` — small MUI `Chip` + dropdown menu, for Status / Team /
  Owner (left) and the Sort control (right, "Sort: Health ⌄").
- `components/ui/ProjectCard/` — composes the above: project name + `StatusBadge` (top
  row), team name, `AvatarGroup`, "Progress" label + percentage + `LinearProgress`, a
  footer stats row ("`{total} total  {n} in progress  {n} blocked`"), and an "Updated
  {relative time} ago" caption.

## Page structure (top to bottom)

1. **Header block** — title "Projects" + subtitle "Every active project, at a glance —
   open one to see process status and the people on it." Reuse `PageContainer` (same
   pattern as `app/(app)/manager/workload/page.tsx`).
2. **Filters row** — `FilterChip` × 3 (Status / Team / Owner) left-aligned, `FilterChip`
   for sort right-aligned.
3. **Summary strip** — `KpiTile` × 4 in a row.
4. **Project grid** — responsive grid (3 columns on desktop) of `ProjectCard`, 12 in the
   mock data.

## Functional requirement flagged by the team lead: sorting must actually work

In the Figma mock, "Sort: Health" is shown as the selected control, but the card order is
**not** actually grouped by health (Healthy/At Risk/Critical are interleaved) — that's
static example data, not a working sort. In the real implementation, selecting a sort
option must genuinely reorder the cards (e.g. Critical → At Risk → Healthy → No data —
confirm the exact intended order with the mentor/team lead). Implement this as real
client-side sort logic (or a query param passed to the mock/real endpoint), not a
decorative control.

## Answers to open questions (for context, not yet confirmed with the mentor)

- **Can a new project be added from this UI?** The screenshot has no "add project"
  control anywhere (header, filters row). Combined with how the rest of this system is
  architected (projects are aggregated from Bridge's external integrations — GitHub,
  Jira, CRM — not manually created in LENS), projects are almost certainly **BE-driven,
  read-only from this page**. Worth a one-line confirmation from the mentor, but don't
  build an "add project" flow unless told to.
- **Is "Updated X ago" real-time?** No — nothing in this frontend today does polling or
  WebSocket updates (checked: every existing data page, e.g. `manager/workload/page.tsx`,
  fetches once on mount via `useEffect` and never refetches on its own). "Updated 2h ago"
  is almost certainly a plain timestamp field in the API response (e.g. `lastUpdatedAt`),
  rendered as relative time client-side once per page load — not a live-updating value.
  True real-time would need new infrastructure (polling interval or WebSocket) that
  doesn't exist anywhere in this codebase yet; raise it with the mentor as a scope
  question for v1 rather than assuming it's needed here.

## Data

No backend endpoint exists yet for "list projects with KPIs/health" — checked
`services/workspaceApi.ts` and `docs/api-integration-status.md`; only task-level endpoints
exist today (`/api/workspace/*`), nothing project-aggregate.

Follow the same real-fetch-through-a-mock pattern already used for `getMyTask` /
`getManagerWorkload` (see `mocks/handlers.ts` + `services/managerApi.ts`):

- Add `getProjects()` to a new `services/projectsApi.ts` (or extend `managerApi.ts`).
- Add a matching MSW handler in `mocks/handlers.ts` returning 12 seed projects: name,
  team, owner/avatars, status tone, progress %, total/in-progress/blocked counts, and a
  `lastUpdatedAt` timestamp.
- Use the same loading/empty/error state pattern as `manager/workload/page.tsx`
  (`LoadingState` / `EmptyState` / `ErrorState` with `useState`/`useEffect`/`useCallback`).

## Suggested build order

1. Re-pull `get_design_context` on `4:21` for exact colors/spacing/copy.
2. Build `KpiTile` and `FilterChip` against `theme/` tokens (check `theme/palette.ts`'s
   `status` palette — same one `StatusBadge` uses).
3. Build `ProjectCard`, composing `StatusBadge` + `AvatarGroup` + `LinearProgress`.
4. Add mock data (`mocks/handlers.ts`), the service function, and real sort logic.
5. Build `app/(app)/manager/projects/page.tsx` composing everything; wire the nav entry.
6. Wire loading/empty/error states.
7. Validate: `npx tsc --noEmit`, `npm run lint`, then check
   `http://localhost:3000/manager/projects` manually — including that clicking "Sort:
   Health" actually reorders the cards.
