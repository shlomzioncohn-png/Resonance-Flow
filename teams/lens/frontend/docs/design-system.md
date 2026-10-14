# LENS Design System Foundation

LENS is built on **MUI** (Material UI). This document is about the layer LENS
adds on top of it: a theme, a set of design tokens, and conventions for using
MUI consistently across the product. It is **not** a component library — MUI
already provides `Button`, `Card`, `Table`, `Chip`, etc. LENS's job is to make
those components look and behave consistently, not to rebuild them.

> If you're about to write a component that only renders a MUI component with
> different default props and no new behavior, stop — use the MUI component
> directly with `sx`, or add a `styleOverrides` entry to `theme/components.ts`
> if the change should apply everywhere. See "When to create a LENS
> component" below.

## File map

```
theme/
  tokens.ts        Raw values — the only file with actual hex/px numbers
  palette.ts        MUI palette, built from tokens.ts + the `status` semantic group
  typography.ts      MUI typography scale, built from tokens.ts
  custom.ts          Tokens MUI's theme shape has no slot for (radius scale, elevation)
  components.ts      Per-component style overrides + global/base styles + a11y baseline
  index.ts           createTheme() — assembles the above into the one `theme` export
```

`app/ThemeRegistry.tsx` imports `theme` from `@/theme` and provides it via
MUI's `ThemeProvider`, wrapped in `AppRouterCacheProvider` for Next.js App
Router SSR. It's mounted once, in `app/layout.tsx`.

## Design tokens

All tokens live in `theme/tokens.ts`. Nothing outside that file should
contain a raw hex color, px radius, or font-weight number — reference the
token (via the theme) instead, so a rebrand or dark-mode pass touches one
file.

### Colors

| Token                                 | Value                 | Used for                                                            |
| ------------------------------------- | --------------------- | ------------------------------------------------------------------- |
| `colorTokens.brand[600]`              | `#4f46e5`             | `palette.primary.main` — buttons, links, active nav, focus ring     |
| `colorTokens.slate[50..950]`          | `#f8fafc` → `#0b1120` | `palette.grey`, text, dividers, the dark sidebar surface            |
| `colorTokens.green` / `amber` / `red` | —                     | `palette.success` / `warning` / `error` (form & feedback semantics) |

### Status palette (`theme.palette.status`)

A second, deliberately separate semantic group for **workflow-health**
meaning — task/project/blocker state — as opposed to MUI's `success` /
`warning` / `error`, which are reserved for form and feedback semantics
(validation messages, snackbars, alerts). Overloading `error` to also mean
"this task is blocked" would make snackbar styling and workflow-health
styling impossible to change independently.

| Tone       | `main` (dot / border) | `bg` (fill) | `text` (label) | Meaning                         |
| ---------- | --------------------- | ----------- | -------------- | ------------------------------- |
| `healthy`  | green 600             | green 50    | green 700      | Healthy / Good / Completed      |
| `warning`  | amber 600             | amber 50    | amber 700      | At risk / In progress / Delayed |
| `critical` | red 600               | red 50      | red 700        | Critical / Blocked              |
| `neutral`  | slate 500             | slate 100   | slate 700      | Unknown / N/A                   |

Usage: `theme.palette.status.healthy.main`, or via `StatusBadge` (see below).
`text` is always a dark, accessible shade — never white-on-tint — so labels
stay readable at the small sizes status badges are used at.

### Typography

Font family: system stack with **Inter** preferred (`theme/tokens.ts →
fontTokens.family`). Scale is defined once in `theme/typography.ts` (h1–h6,
subtitle1/2, body1/2, caption, overline, button) — use MUI's `variant` prop
(`<Typography variant="h6">`) rather than ad hoc `fontSize`/`fontWeight` in
`sx`. If a screen genuinely needs a size the scale doesn't have, add it to
`theme/typography.ts`, don't one-off it in `sx`.

### Spacing

MUI's spacing function, base unit **8px** (`theme/tokens.ts →
spacingUnit`). Always use `theme.spacing(n)` or the numeric `sx` shorthand
(`sx={{ p: 2, gap: 1.5 }}`, `<Stack spacing={2}>`) — never a raw `px` value
for spacing.

### Border radius

`theme.shape.borderRadius` (MUI's built-in single radius, used implicitly by
most components) is `10px`. For places that need a different step, use
`theme.custom.radius`:

| Token         | Value | Used for                                   |
| ------------- | ----- | ------------------------------------------ |
| `radius.sm`   | 6px   | Buttons, list items, small controls        |
| `radius.md`   | 10px  | Cards, panels (`theme.shape.borderRadius`) |
| `radius.lg`   | 16px  | Larger surfaces, modals                    |
| `radius.pill` | 999px | Chips, status badges                       |

### Elevation

MUI's default 25-step `shadows` array is overkill for this product's mostly
flat, bordered-card look (see the manager dashboard reference design — cards
use a hairline border and a very soft shadow, not heavy elevation). Instead of
redefining all 25 steps, `theme.custom.elevation` gives three named,
purpose-built shadows:

| Token               | Used for                                |
| ------------------- | --------------------------------------- |
| `elevation.card`    | Default card/panel shadow (very subtle) |
| `elevation.raised`  | Hover/dragging state                    |
| `elevation.overlay` | Menus, popovers, dialogs                |

### Breakpoints

MUI's defaults, declared explicitly in `theme/tokens.ts` so they're a
documented LENS token rather than an implicit default: `xs 0 / sm 600 / md
900 / lg 1200 / xl 1536`. The App Shell's mobile/desktop split (Sidebar
permanent vs. Drawer) switches at `md`.

## Global / base styles

Set in `theme/components.ts` under `MuiCssBaseline.styleOverrides` (MUI's
mechanism for global styles — this is the one place a `body { ... }` /
`* { ... }` rule belongs, instead of a separate global stylesheet). Currently:

- Page background from `theme.palette.background.default`.
- A visible `:focus-visible` ring on every element (accessibility baseline,
  see below).
- `prefers-reduced-motion: reduce` support — shortens/disables
  animations and transitions app-wide for users who've asked for it.

`app/globals.css` is intentionally minimal (viewport sizing only) — anything
color/theme-related belongs in `theme/components.ts`, not there.

## MUI usage conventions

1. **Read colors, spacing, and radii from the theme, not literals.** `sx={{
bgcolor: 'primary.main' }}` or `bgcolor: 'grey.100'`, not `bgcolor:
'#4f46e5'`. If a value doesn't exist on the theme yet, add it to
   `theme/tokens.ts` rather than inlining it — see `Sidebar.tsx` and
   `Header.tsx` for the pattern (no component in `components/layout` hardcodes
   a color).
2. **A rule that applies to every instance of a component belongs in
   `theme/components.ts`**, not repeated in every file's `sx` prop. Example:
   "no button ever has uppercase text" is set once via `MuiButton.styleOverrides`,
   not passed as `sx={{ textTransform: 'none' }}` on every `<Button>`. A
   one-off, single-instance tweak stays local, in that component's `sx`.
3. **Prefer `sx` over `styled()`** for one-off layout/spacing on a specific
   element. Reach for `theme/components.ts` overrides for anything systemic.
4. **Icons**: `@mui/icons-material`, and prefer the `...Outlined` family for
   navigation/toolbar icons (matches the current App Shell) unless a filled
   icon communicates state (e.g. a filled icon for an active/selected item).
5. **Don't redefine typography inline.** Use `variant` props from the scale in
   `theme/typography.ts`.
6. **Extend the theme via TypeScript module augmentation**, not by bolting
   extra props onto components. `theme/palette.ts` and `theme/custom.ts` show
   the pattern (`declare module '@mui/material/styles' { ... }`) for adding
   the `status` palette group and `theme.custom`. Use the same pattern if a
   future need requires a new palette group or custom token category.
7. **No pass-through wrapper components.** Don't create
   `components/ui/LensButton` that renders `<Button {...props} />` with no
   added behavior — it adds an indirection with no value and a second place
   for `Button` conventions to drift out of sync. Style `Button` globally via
   `theme/components.ts` instead.

## When to create a LENS-specific component

Only when there's a real **product abstraction or behavior** MUI doesn't
express — not to rename or reskin an existing MUI component. Ask: _does this
encode domain knowledge (a mapping, a business rule, a composed
interaction), or is it just MUI with different default styling?_ The latter
belongs in `theme/components.ts`.

**Example that qualifies:** `components/ui/StatusBadge`. Workflow-health
status (Healthy / At risk / Critical / Unknown) is a LENS domain concept with
no MUI equivalent — `Chip`'s built-in `color` prop is form/feedback semantics
(`success`/`warning`/`error`/`info`), not this vocabulary. `StatusBadge`
composes `Chip` with the `status` palette and always pairs a color with a
text label (never color alone — see Accessibility), which is exactly the
kind of small, justified abstraction this rule allows:

```tsx
import StatusBadge from '@/components/ui/StatusBadge';

<StatusBadge status="critical" label="Blocked" />;
```

See it in use in `app/(app)/manager/workload/page.tsx`.

**Example that would not qualify:** a `Card` wrapper that just sets a
default `elevation={0}` — that's a per-instance default, and belongs in
`theme/components.ts → MuiCard.styleOverrides` so every `<Card>` gets it
automatically, including ones written before the wrapper existed.

## Working example: Theme usage in the App Shell

The App Shell (`components/layout/{AppShell,Header,Sidebar,Logo,PageContainer}`)
is the theme's first real consumer and demonstrates the conventions above:

- `Sidebar.tsx` is a permanently dark surface (a deliberate product choice,
  independent of light/dark mode) built entirely from theme values —
  `theme.palette.grey[900]` for the background, `alpha(theme.palette.common.white,
…)` for text/hover/divider opacity steps, `theme.typography.*.fontWeight`
  for active vs. inactive nav item weight. No hex literal appears in the file.
- `Header.tsx` uses `theme.custom.radius.sm` for the search field and
  `grey.100` for its background.
- `Logo.tsx` uses `primary.main` / `primary.contrastText` (via `currentColor`
  in the inline SVG) instead of a hardcoded white, so the mark stays correct
  if the primary color's contrast requirement ever changes.
- `PageContainer.tsx` and the `feedback/*State` components use `variant`
  typography props and palette color keys (`text.secondary`, `error.main`,
  `status.critical.bg`) exclusively.

## Accessibility baseline

- **Focus visibility**: every interactive element gets a 2px `primary.main`
  outline on keyboard focus (`*:focus-visible` in `theme/components.ts`),
  independent of MUI's ripple/hover effects.
- **Reduced motion**: `prefers-reduced-motion: reduce` is honored globally.
- **Color is never the only signal.** `StatusBadge` always renders a text
  label next to its color dot. Follow the same rule for any new
  status/severity UI (WCAG 1.4.1).
- **Icon-only controls need an accessible name.** Use `aria-label` on the
  `IconButton` itself (not just a `Tooltip` title) — see the notifications
  button in `Header.tsx`.
- **Live regions for async state**: `LoadingState` uses `role="status"
aria-live="polite"`; `ErrorState` uses `role="alert"`. Reuse these
  components for new async views instead of building ad hoc loading/error UI.
- **Contrast**: `text.primary` on `background.default`/`paper` and all four
  `status` tones' `text` color on their `bg` fill meet WCAG AA (≥4.5:1) at
  body-text sizes. If you introduce a new color pairing, check contrast
  before shipping it.
- **Keyboard navigation**: Sidebar and Header use native `<a>` (via Next
  `Link`) and MUI's `ButtonBase`-derived components, so tab order and
  Enter/Space activation work without extra wiring — don't replace them with
  `<div onClick>`.

This is a baseline, not a full audit — a dedicated accessibility pass is
planned for Phase 2 per the LENS roadmap.

### Enforcement: ESLint

`eslint.config.mjs` layers `eslint-plugin-jsx-a11y`'s full `recommended` rule
set on top of `eslint-config-next`'s (Next only enables a small subset, as
warnings). Run `npm run lint` before committing — this is a real gate, not
advisory: an accessibility regression (a missing `alt`, an interactive `div`,
an unlabeled control) fails the same way a type error would.

## Extending the theme

- New raw value → `theme/tokens.ts`.
- New palette color/group → `theme/palette.ts` (module-augment `Palette`/
  `PaletteOptions` if it's a new group, like `status`).
- New typography variant → `theme/typography.ts`.
- New non-palette token category (spacing-adjacent, elevation-adjacent) →
  `theme/custom.ts`, augmenting `Theme`/`ThemeOptions` with a new key under
  `theme.custom`.
- New "every instance of this MUI component looks/behaves like X" rule →
  `theme/components.ts`.

Everything is assembled in `theme/index.ts` via a single `createTheme()`
call — don't call `createTheme()` a second time anywhere else in the app.
