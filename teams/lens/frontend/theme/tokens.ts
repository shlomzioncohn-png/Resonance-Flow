/**
 * LENS design tokens — the single source of raw values the theme is built from.
 *
 * Nothing in `app/` or `components/` should hardcode a hex color, px radius, or
 * font size. Add or change a value here, and it flows through `theme/` into
 * every MUI component automatically. See `docs/design-system.md` for the
 * conventions that go with these tokens.
 */

export const colorTokens = {
  brand: {
    50: '#eef0ff',
    100: '#e0e3ff',
    200: '#c3c8ff',
    300: '#9ca3f7',
    400: '#7c7ff2',
    500: '#6366f1',
    600: '#4f46e5',
    700: '#4338ca',
    800: '#372fa3',
    900: '#2e2882',
  },
  slate: {
    50: '#f8fafc',
    100: '#f1f5f9',
    200: '#e2e8f0',
    300: '#cbd5e1',
    400: '#94a3b8',
    500: '#64748b',
    600: '#475569',
    700: '#334155',
    800: '#1e293b',
    900: '#0f172a',
    950: '#0b1120',
  },
  green: { 50: '#f0fdf4', 100: '#dcfce7', 500: '#22c55e', 600: '#16a34a', 700: '#15803d' },
  amber: { 50: '#fffbeb', 100: '#fef3c7', 500: '#f59e0b', 600: '#d97706', 700: '#b45309' },
  red: { 50: '#fef2f2', 100: '#fee2e2', 500: '#ef4444', 600: '#dc2626', 700: '#b91c1c' },
  gray: { 50: '#f8fafc', 100: '#f1f5f9', 500: '#94a3b8', 600: '#64748b', 700: '#475569' },
  violet: { 50: '#f5f3ff', 500: '#8b5cf6' },
  cyan: { 600: '#0891b2' },
  pink: { 500: '#ec4899' },
} as const;

/** The look of AI-generated content (Personal Work Insights), so it reads apart from regular data. */
export const aiTokens = {
  gradient: `linear-gradient(135deg, ${colorTokens.brand[600]} 0%, ${colorTokens.violet[500]} 55%, ${colorTokens.pink[500]} 100%)`,
  surface: colorTokens.violet[50],
  accent: colorTokens.violet[500],
  glow: '0 10px 24px -8px rgba(79, 70, 229, 0.7)',
} as const;

/** Semantic status meaning used across the product (see the status legend in the manager dashboard design). */
/**
 * One color per task state in charts, in this order (stacks and legends
 * follow it). Checked for color-blind separation and contrast on white -
 * change them together, not one at a time.
 */
export const chartTokens = {
  inProgress: colorTokens.brand[600],
  snoozed: colorTokens.amber[600],
  open: colorTokens.cyan[600],
  done: colorTokens.green[600],
} as const;

export const statusTokens = {
  healthy: {
    main: colorTokens.green[600],
    bg: colorTokens.green[50],
    text: colorTokens.green[700],
  },
  warning: {
    main: colorTokens.amber[600],
    bg: colorTokens.amber[50],
    text: colorTokens.amber[700],
  },
  critical: {
    main: colorTokens.red[600],
    bg: colorTokens.red[50],
    text: colorTokens.red[700],
  },
  neutral: {
    main: colorTokens.gray[500],
    bg: colorTokens.gray[100],
    text: colorTokens.gray[700],
  },
} as const;

export const fontTokens = {
  family:
    '-apple-system, BlinkMacSystemFont, "Segoe UI", Inter, Roboto, Helvetica, Arial, sans-serif',
  weight: {
    regular: 400,
    medium: 500,
    semibold: 600,
    bold: 700,
  },
} as const;

/**
 * Base spacing unit, in px. Consumed by MUI's `theme.spacing()` — every
 * spacing value in the app should be a multiple of this via `theme.spacing(n)`
 * or the `sx` shorthand (`p={2}` etc.), never a raw px value.
 */
export const spacingUnit = 8;

export const radiusTokens = {
  sm: 6,
  md: 10,
  lg: 16,
  pill: 999,
} as const;

export const elevationTokens = {
  card: '0 1px 2px rgba(15, 23, 42, 0.04)',
  raised: '0 4px 12px rgba(15, 23, 42, 0.08)',
  overlay: '0 12px 32px rgba(15, 23, 42, 0.16)',
} as const;

/** Matches MUI's default breakpoints — declared explicitly so they're documented as a LENS token, not an implicit default. */
export const breakpointTokens = {
  xs: 0,
  sm: 600,
  md: 900,
  lg: 1200,
  xl: 1536,
} as const;
