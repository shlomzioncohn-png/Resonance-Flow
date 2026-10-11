# LENS Frontend

Frontend foundation for the LENS team in Resonance Flow. This is a React and Next.js application
using Material UI (MUI).

## Stack

- React
- TypeScript
- Next.js
- Material UI (MUI)
- Docker and Docker Compose

## Project structure

```
app/                        Next.js routes, layouts, global styles, MUI theme
  (app)/                      Routes wrapped in the App Shell (Header + Sidebar)
  (public)/                   Routes with no shell (/login, /unauthorized)
components/
  layout/                     AppShell, Header, Sidebar, PageContainer, Logo
  feedback/                   LoadingState, EmptyState, ErrorState
  ui/                          (reserved for shared, non-layout UI components)
mocks/                       Mock data adapters used until real APIs exist
services/                    Gateway API client (api.ts) — not wired into any page yet;
                              see docs/api-integration-status.md for why
docs/                        Architecture docs (see below)
features/,                  Reserved for upcoming feature work — not used yet
hooks/, config/,
types/, utils/, tests/
Dockerfile                  Production container image
docker-compose.yml          Local container orchestration
```

See [docs/app-shell-and-routing.md](docs/app-shell-and-routing.md) for the
routing structure, the App Shell components, and how to add a new page.

## Design system (LENS on MUI)

LENS does not build its own component library — it uses **MUI** directly and
adds one layer on top: a shared theme, a small set of design tokens, and a
few conventions so every screen looks and behaves consistently. Full
reference: [docs/design-system.md](docs/design-system.md). This section is
the fast-start version.

### What's already built

- **`theme/`** — the single source of truth, assembled by `theme/index.ts`
  into one `createTheme()` call, provided app-wide via `app/ThemeRegistry.tsx`:
  - `tokens.ts` — raw values (brand color, grey scale, semantic colors, font
    stack, spacing unit, breakpoints).
  - `palette.ts` — MUI's `palette`, plus a LENS-specific
    `palette.status.{healthy,warning,critical,neutral}` group for
    workflow-health state (task/project/blocker status) — kept separate from
    MUI's `success`/`warning`/`error`, which stay reserved for form/feedback
    semantics.
  - `typography.ts` — the full type scale (h1–h6, body, caption, etc.).
  - `custom.ts` — tokens MUI has no built-in slot for: a border-radius scale
    (`radius.sm/md/lg/pill`) and named elevation shadows (`elevation.card/
raised/overlay`).
  - `components.ts` — per-component style overrides (`Button`, `Card`,
    `Chip`, `AppBar`, …), global/base styles via `MuiCssBaseline`, and the
    accessibility baseline (see below).
- **`components/ui/StatusBadge`** — the one LENS-specific component created so
  far. It maps a workflow-health status to a colored label
  (`<StatusBadge status="critical" label="Blocked" />`), used in
  `app/(app)/manager/workload/page.tsx`.
- **Accessibility baseline** — a visible `:focus-visible` ring on every
  interactive element, `prefers-reduced-motion` support, live regions on
  `LoadingState`/`ErrorState`, and a rule that status is never conveyed by
  color alone.
- The existing App Shell (`Sidebar`, `Header`, `Logo`, `feedback/*State`) was
  refactored to consume theme tokens — no component under `components/layout`
  hardcodes a color, radius, or font size.

### How to use it

- **Colors, spacing, radius** — read from the theme, never hardcode:
  ```tsx
  <Box sx={{ bgcolor: 'primary.main', p: 2, borderRadius: (t) => t.custom.radius.md }} />
  ```
- **Typography** — use the `variant` prop, don't set `fontSize`/`fontWeight`
  ad hoc:
  ```tsx
  <Typography variant="h6">Section title</Typography>
  ```
- **Workflow-health status** — use `StatusBadge`, not a raw `Chip`:
  ```tsx
  import StatusBadge from '@/components/ui/StatusBadge';
  <StatusBadge status="healthy" label="On track" />;
  ```
- **A style that should apply to every instance of a MUI component** (e.g.
  "buttons are never uppercase") goes in `theme/components.ts`, not repeated
  as `sx` on every usage.

### How to conduct yourself (rules)

1. No raw hex/px values outside `theme/tokens.ts` — add a token instead of
   inlining a value.
2. No pass-through wrapper components (no `LensButton` that just renders
   `<Button {...props} />`). Style MUI components globally via
   `theme/components.ts`.
3. Only create a new `components/ui/*` component when it encodes real product
   behavior or domain meaning that MUI has no vocabulary for — the same bar
   `StatusBadge` had to clear. Otherwise, use the MUI component directly.
4. Extend the theme by editing the relevant `theme/*.ts` file (see the
   "Extending the theme" section of `docs/design-system.md`), not by
   creating a second, local theme or a separate stylesheet.
5. Never let color be the only signal of state/status — pair it with a text
   label or icon.

See [docs/design-system.md](docs/design-system.md) for the full token
reference, the reasoning behind each convention, and worked examples.

## Run locally

Requires Node.js and npm.

```bash
npm ci
npm run dev
```

The development server is available at http://localhost:3000.

To create a production build:

```bash
npm run build
```

To lint (includes accessibility checks — see [Design system](#design-system-lens-on-mui)):

```bash
npm run lint
```

## Storybook

Every shared component has stories next to it (`components/**/<Name>.stories.tsx`).
Storybook is both the component catalogue and a test suite: each story is rendered
in a real browser in CI, its `play` function is run, and it is checked for
accessibility violations.

### Run locally

```bash
npm run storybook                       # dev server at http://localhost:6006
npx vitest run --project=storybook      # run every story as a test (what CI runs)
npm run build-storybook                 # static build into storybook-static/
```

The story tests need a browser once: `npx playwright install chromium`.

> On Windows, run the story tests from a path with ASCII characters only. If the
> checkout sits under a non-ASCII folder (e.g. a Hebrew user name), Vitest reports
> `No test suite found` for every story.

### Write a new story

Create `<Name>.stories.tsx` beside the component:

```tsx
import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { expect } from 'storybook/test';
import MyComponent from './MyComponent';

const meta = {
  component: MyComponent,
  tags: ['autodocs'], // generates the component's Docs page
} satisfies Meta<typeof MyComponent>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  args: { title: 'Hello' },
  play: async ({ canvas }) => {
    await expect(canvas.getByText('Hello')).toBeVisible();
  },
};
```

- One story per meaningful state (empty, loading, error, each variant).
- Always keep `tags: ['autodocs']` - that is what gives the component a Docs page.
- The MUI theme and `RoleProvider` are already applied to every story in
  `.storybook/preview.tsx`; don't re-wrap them.
- A component that reads `usePathname()`/`useRouter()` needs
  `parameters: { nextjs: { appDirectory: true, navigation: { pathname: '/' } } }`
  (see `Sidebar.stories.tsx`).
- Accessibility violations **fail** the test (`a11y.test: 'error'` in
  `.storybook/preview.tsx`). Fix the component rather than turning the check off.
  A few stories disable the single `color-contrast` rule with a `TODO(a11y)` note -
  those are known palette issues waiting on a design decision, not a pattern to copy.

### CI and the deployed Storybook

- **Pull requests** - the `storybook` job in `.github/workflows/lens-frontend-ci.yaml`
  runs the story tests and `build-storybook`.
- **Deploy** - on push to `main`, `.github/workflows/lens-storybook-ci.yaml` builds the
  `storybook` stage of the `Dockerfile` (static build served by nginx on port 8080) and
  deploys it as the `lens-storybook` component of the shared Helm chart.

To open the Storybook running in the cluster:

```bash
kubectl -n resonance-flow port-forward svc/resonance-flow-lens-storybook 6006:80
```

then browse to http://localhost:6006.

## Run with Docker

Build and start the frontend:

```bash
docker compose up --build
```

The application is available at http://localhost:3000. To use another host port, set
`FRONTEND_PORT`:

```bash
FRONTEND_PORT=8081 docker compose up --build
```

Stop the container with:

```bash
docker compose down
```
