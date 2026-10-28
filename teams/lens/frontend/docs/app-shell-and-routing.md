# App Shell & Routing

This is the foundation every LENS screen builds on: a shared `AppShell` (Header +
Sidebar + main content area) plus a routing skeleton for the app's main zones.
Every route under `(app)` needs a signed-in user with the right role — see
[Sign-in and roles](#sign-in-and-roles) below.

## Route structure

Routes live under `app/` using two Next.js route groups. A route group's folder
name in parentheses does not appear in the URL — it only controls which layout a
page uses.

```
app/
├── layout.tsx            Root layout: <html>/<body>, ThemeRegistry (MUI theme + SSR cache)
├── not-found.tsx           Global 404, styled like the public pages (no shell)
│
├── (app)/                  Routes wrapped in the App Shell (Header + Sidebar)
│   ├── layout.tsx           Renders <AppShell>{children}</AppShell>
│   ├── page.tsx              "/"                    no screen - redirects to the role's home
│   ├── manager/
│   │   ├── page.tsx           "/manager"             Dashboard: KPIs, charts (sample data for now), top bottlenecks
│   │   ├── projects/page.tsx  "/manager/projects"    all projects: filters, KPIs, project cards
│   │   ├── projects/[projectId]/page.tsx "/manager/projects/:projectId" drill-down, not in nav
│   │   ├── workload/page.tsx  "/manager/workload"    demo page (see below)
│   │   ├── wip/page.tsx       "/manager/wip"
│   │   ├── blocked/page.tsx   "/manager/blocked"
│   │   ├── bottlenecks/page.tsx "/manager/bottlenecks" dependency graph; ?projectId= filter, ?taskId= open task
│   │   └── users/page.tsx     "/manager/users"
│   ├── employee/
│   │   ├── page.tsx                     "/employee"                  My Workspace — time-boxed task recommendation
│   │   ├── tasks/page.tsx               "/employee/tasks"            My Tasks — search, status filter, paging
│   │   └── tasks/[taskId]/page.tsx      "/employee/tasks/:taskId"    Task Details, drill-down, not in nav
│   └── team/[employeeId]/page.tsx     "/team/:employeeId"   drill-down, not in nav
│
└── (public)/                Routes with NO shell (no Header/Sidebar)
    ├── login/page.tsx         "/login"
    └── unauthorized/page.tsx  "/unauthorized"
```

The route names and the manager/employee split follow the team's route map
(`route-map_2.md`). `/login` and `/unauthorized` are deliberately outside the
`(app)` group: a signed-out user never sees the internal navigation, so they get
the root layout only, styled to match.

## App Shell

| Component       | Location                          | Responsibility                                                                                                                                                                                 |
| --------------- | --------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `AppShell`      | `components/layout/AppShell`      | Composes Header + Sidebar + main content area; holds the mobile-drawer open/close state. Sends signed-out users to `/login` and wrong-role users to `/unauthorized`.                          |
| `Header`        | `components/layout/Header`        | Top bar: mobile menu toggle, search field (visual placeholder, not wired up), notifications icon, signed-in user's name and role, Sign out button.                                            |
| `Sidebar`       | `components/layout/Sidebar`       | Left navigation. Dark, full-height. Permanent on desktop (`md` and up), a slide-over `Drawer` on mobile. Nav items come from `navConfig.tsx`. Highlights the active route via `usePathname()`. |
| `Logo`          | `components/layout/Logo`          | Brand mark used in the Sidebar and on the public pages (`light`/`dark` variant for background contrast).                                                                                       |
| `PageContainer` | `components/layout/PageContainer` | Consistent page wrapper: title, optional description, optional right-aligned actions, max-width content column. Every page under `(app)` renders its content through this.                     |

`AppShell` is only applied by `app/(app)/layout.tsx`. Pages under `(public)/`
build their own minimal centered layout instead (see `login/page.tsx`).

## Sign-in and roles

- `/login` sends email + password to the Gateway (`POST /auth/login`). The Gateway
  puts the token in an HttpOnly cookie, and returns the user's name and role
  (`TENANT_ADMIN`, `TEAM_LEAD` or `EMPLOYEE`).
- `components/layout/RoleProvider` keeps that user (never the token) in React context
  and in `sessionStorage`, so a refresh or a typed URL doesn't lose it. It's mounted
  once in `app/layout.tsx`, so both route groups share it.
- `navConfig.tsx` says which roles see each Sidebar section (`roles`), where each role
  lands (`homeFor`) and which paths it may open (`canAccess`). Managers
  (`TENANT_ADMIN`, `TEAM_LEAD`) get `/manager/**` and `/team/**`;
  employees get `/employee/**`.
- `AppShell` checks every screen: signed out → `/login`, wrong role → `/unauthorized`.
- The Header's Sign out button calls `POST /auth/logout` and clears the user.

This is the browser side only. Someone can still edit `sessionStorage` by hand, so the
real protection must be the Gateway checking the role on every `/api/**` request.

## Feedback states

`components/feedback/{LoadingState,EmptyState,ErrorState}` are the shared,
reusable building blocks for async UI. They take a title/description and, for
`EmptyState`/`ErrorState`, an optional action/retry slot. Every placeholder page
currently renders `EmptyState` with "coming soon" copy — swap it for real content
once a page has data.

## Demo page: `/manager/workload`

This page is the one required by the DoD to "demonstrate a page integrated with
the Shell" end to end. It calls a mock adapter (`mocks/managerWorkload.ts`) that
simulates a network delay and always resolves empty, so the page actually walks
through **Loading → Empty**, and its "Simulate error state" button demonstrates
**Error** (`Try again` re-triggers the fetch). This is the pattern future pages
should follow once they call a real adapter instead of a mock.

## Adding a new page

1. Pick the route group:
   - Behind the shell (needs Header/Sidebar) → create it under `app/(app)/...`.
   - Public, pre-auth → create it under `app/(public)/...`.
2. Add `page.tsx` with a default-exported component. Wrap its content in
   `PageContainer` (from `@/components/layout/PageContainer`) for the standard
   title/description/spacing.
3. If the page fetches data, model it as `loading` / `empty` / `error` / (real
   content) and render `LoadingState` / `EmptyState` / `ErrorState` from
   `@/components/feedback/...` for the non-happy paths — see
   `app/(app)/manager/workload/page.tsx` for the pattern.
4. If the page should appear in the left navigation, add an entry to
   `components/layout/Sidebar/navConfig.tsx` (drill-down pages like
   `/team/:id` are intentionally left out of the nav — they're only reached by
   a link from another page).
5. Run `npm run lint` (accessibility, React, and Next.js rules) and `npm run
build` (typecheck + production build) before committing.

## Notes for the next contributor

- Path alias: `@/*` resolves to the frontend project root (configured in
  `tsconfig.json`), so components/mocks can be imported as
  `@/components/...` / `@/mocks/...` from anywhere under `app/`.
- `typescript` is pinned to `5.9.3` in `package.json` (not `"latest"` like the
  other dependencies). At the time this foundation was built, `typescript@latest`
  resolved to an early `7.x` prerelease whose stricter type-checking rejected
  valid `@mui/material` v9 prop usage (see below). Re-evaluate the pin once
  MUI's types are confirmed compatible with TypeScript 7.
- MUI v9 dropped several prop shorthands that older MUI docs/examples still
  show: `Stack`'s `alignItems`/`justifyContent`, `Typography`'s `fontWeight`,
  and `ListItemText`'s `primaryTypographyProps` are no longer typed as direct
  props — pass them through `sx` (or `slotProps` for `ListItemText`) instead.
- A component that passes `component={SomeComponent}` (e.g.
  `<Button component={Link}>`) needs to be a Client Component (`'use client'`)
  when used from a page that is otherwise a Server Component — passing a
  component reference across the server/client boundary isn't allowed
  otherwise. `not-found.tsx`, `(public)/login` and `(public)/unauthorized` are
  `'use client'` for this reason.
