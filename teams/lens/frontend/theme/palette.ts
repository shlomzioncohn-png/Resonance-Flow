import type { PaletteOptions } from '@mui/material/styles'
import { colorTokens, statusTokens } from './tokens'

/**
 * Adds a `status` palette group (healthy / warning / critical / neutral) for
 * workflow-health semantics — used by `StatusBadge` and anywhere else that
 * shows a health state. This is additive to MUI's palette, not a replacement
 * for `success`/`warning`/`error`: those stay reserved for form/feedback
 * semantics (validation, snackbars), while `status` is reserved for
 * workflow-health semantics (tasks, projects, blockers). Keeping them
 * separate avoids overloading `error` for both "form failed" and "task
 * blocked".
 */
declare module '@mui/material/styles' {
  interface StatusPaletteEntry {
    main: string
    bg: string
    text: string
  }

  interface Palette {
    status: {
      healthy: StatusPaletteEntry
      warning: StatusPaletteEntry
      critical: StatusPaletteEntry
      neutral: StatusPaletteEntry
    }
  }

  interface PaletteOptions {
    status?: Palette['status']
  }
}

export const palette: PaletteOptions = {
  mode: 'light',
  primary: {
    main: colorTokens.brand[600],
    light: colorTokens.brand[500],
    dark: colorTokens.brand[700],
    contrastText: '#ffffff',
  },
  error: {
    main: colorTokens.red[600],
    light: colorTokens.red[500],
    dark: colorTokens.red[700],
  },
  warning: {
    main: colorTokens.amber[600],
    light: colorTokens.amber[500],
    dark: colorTokens.amber[700],
  },
  success: {
    main: colorTokens.green[600],
    light: colorTokens.green[500],
    dark: colorTokens.green[700],
  },
  grey: colorTokens.slate,
  background: {
    default: '#ffffff',
    paper: '#ffffff',
  },
  text: {
    primary: colorTokens.slate[900],
    secondary: colorTokens.slate[500],
    disabled: colorTokens.slate[400],
  },
  divider: 'rgba(15, 23, 42, 0.08)',
  status: statusTokens,
}
