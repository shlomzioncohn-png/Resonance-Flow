import { createTheme } from '@mui/material/styles'
import { palette } from './palette'
import { typography } from './typography'
import { components } from './components'
import { custom } from './custom'
import { breakpointTokens, spacingUnit, radiusTokens } from './tokens'

/**
 * The single LENS MUI theme. Everything that makes MUI look and behave like
 * LENS — instead of default MUI — is assembled here from the token/palette/
 * typography/component modules in this folder. Import `theme` from `@/theme`;
 * don't `createTheme()` again anywhere else.
 */
export const theme = createTheme({
  palette,
  typography,
  components,
  custom,
  spacing: spacingUnit,
  shape: {
    borderRadius: radiusTokens.md,
  },
  breakpoints: {
    values: breakpointTokens,
  },
})

export { colorTokens, statusTokens, fontTokens, radiusTokens, elevationTokens, breakpointTokens, spacingUnit } from './tokens'
export type { LensCustomTokens } from './custom'
