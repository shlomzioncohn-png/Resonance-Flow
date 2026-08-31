import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { alpha, type Theme } from '@mui/material/styles'
import { radiusTokens } from '@/theme'

export type Tone = 'error' | 'info' | 'secondary' | 'success'

// Soft "pastel" chip look (light tinted background, matching-color text/icon) built from
// the theme's own palette entries via alpha() — MUI's Chip has no built-in "soft" variant.
export function softToneSx(tone: Tone) {
  return (theme: Theme) => ({
    bgcolor: alpha(theme.palette[tone].main, 0.12),
    color: theme.palette[tone].dark,
    '& .MuiChip-icon': { color: theme.palette[tone].main },
  })
}

export function iconBadgeSx(theme: Theme) {
  return {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    width: 48,
    height: 48,
    flexShrink: 0,
    borderRadius: radiusTokens.md,
    bgcolor: alpha(theme.palette.primary.main, 0.1),
    color: 'primary.main',
  }
}

export function DetailRow({ label, value }: { label: string; value: ReactNode }) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary" component="div">
        {label}
      </Typography>
      <Box sx={{ typography: 'body2', mt: 0.25 }}>{value}</Box>
    </Box>
  )
}
