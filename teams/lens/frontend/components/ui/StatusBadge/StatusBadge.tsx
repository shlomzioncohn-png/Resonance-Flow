'use client'

import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import { useTheme } from '@mui/material/styles'

export type StatusTone = 'healthy' | 'warning' | 'critical' | 'neutral'

export interface StatusBadgeProps {
  /** Workflow-health tone — maps to `theme.palette.status.*`, not to MUI's form/feedback colors. */
  status: StatusTone
  /** Visible label. Status is never conveyed by color alone (WCAG 1.4.1) — always pass a word, not just a color swatch. */
  label: string
}

/**
 * The one LENS-specific component in this design system: a dot + label pill
 * for workflow-health status (Healthy / At risk / Critical / Unknown). MUI
 * has no concept of "workflow health" — `Chip`'s built-in `color` prop maps
 * to form/feedback semantics (`success`/`warning`/`error`), not this domain
 * vocabulary, so this composes `Chip` with the `status` palette instead of
 * wrapping it 1:1. Everywhere else, use MUI components directly.
 */
export default function StatusBadge({ status, label }: StatusBadgeProps) {
  const theme = useTheme()
  const tone = theme.palette.status[status]

  return (
    <Chip
      size="small"
      label={label}
      icon={
        <Box
          aria-hidden="true"
          sx={{
            width: 8,
            height: 8,
            borderRadius: '50%',
            bgcolor: tone.main,
            ml: '8px !important',
          }}
        />
      }
      sx={{
        bgcolor: tone.bg,
        color: tone.text,
        '& .MuiChip-label': { px: 1 },
      }}
    />
  )
}
